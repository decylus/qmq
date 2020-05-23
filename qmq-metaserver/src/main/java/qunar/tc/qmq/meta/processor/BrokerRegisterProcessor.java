/*
 * Copyright 2018 Qunar, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package qunar.tc.qmq.meta.processor;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qunar.tc.qmq.configuration.DynamicConfig;
import qunar.tc.qmq.meta.*;
import qunar.tc.qmq.meta.cache.CachedMetaInfoManager;
import qunar.tc.qmq.meta.model.BrokerMeta;
import qunar.tc.qmq.meta.monitor.QMon;
import qunar.tc.qmq.meta.store.BrokerStore;
import qunar.tc.qmq.meta.store.Store;
import qunar.tc.qmq.netty.NettyRequestProcessor;
import qunar.tc.qmq.protocol.CommandCode;
import qunar.tc.qmq.protocol.Datagram;
import qunar.tc.qmq.protocol.RemotingCommand;
import qunar.tc.qmq.service.HeartbeatManager;
import qunar.tc.qmq.util.RemotingBuilder;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * @author yunfeng.yang
 * @since 2017/8/30
 */
public class BrokerRegisterProcessor implements NettyRequestProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(BrokerRegisterProcessor.class);

    private static final long HEARTBEAT_TIMEOUT_MS = 30 * 1000;

    private final DynamicConfig config;
    private final Store store;
    private final BrokerStore brokerStore;
    private final CachedMetaInfoManager cachedMetaInfoManager;
    private final HeartbeatManager<String> heartbeatManager;

    public BrokerRegisterProcessor(DynamicConfig config, CachedMetaInfoManager cachedMetaInfoManager, Store store, BrokerStore brokerStore) {
        this.store = store;
        this.config = config;
        this.cachedMetaInfoManager = cachedMetaInfoManager;
        this.heartbeatManager = new HeartbeatManager<>();
        this.brokerStore = brokerStore;
    }

    @Override
    public CompletableFuture<Datagram> processRequest(ChannelHandlerContext ctx, RemotingCommand request) {
        final ByteBuf body = request.getBody();
        final BrokerRegisterRequest brokerRequest = BrokerRegisterRequestSerializer.deSerialize(body);
        final String groupName = brokerRequest.getGroupName();
        final int brokerRole = brokerRequest.getBrokerRole();
        final int requestType = brokerRequest.getRequestType();

        QMon.brokerRegisterCountInc(groupName, requestType);

        LOG.info("broker register request received. request: {}", brokerRequest);

        if (brokerRole == BrokerRole.SLAVE.getCode()
                || brokerRole == BrokerRole.DELAY_SLAVE.getCode()
                || brokerRole == BrokerRole.BACKUP.getCode()
                || brokerRole == BrokerRole.DELAY_BACKUP.getCode()) {
            return CompletableFuture.completedFuture(handleSlave(request, brokerRequest));
        }

        return CompletableFuture.completedFuture(handleMaster(request, brokerRequest));
    }

    private Datagram handleSlave(final Datagram request, final BrokerRegisterRequest brokerRequest) {
        final int requestType = brokerRequest.getRequestType();
        if (requestType == BrokerRequestType.HEARTBEAT.getCode()) {
            return handleSlaveHeartbeat(request, brokerRequest);
        } else if (requestType == BrokerRequestType.ONLINE.getCode()) {
            handleIPChange(brokerRequest);
        }
        return RemotingBuilder.buildEmptyResponseDatagram(CommandCode.SUCCESS, request.getHeader());
    }

    private Datagram handleMaster(final Datagram request, final BrokerRegisterRequest brokerRequest) {
        final int requestType = brokerRequest.getRequestType();

        if (requestType == BrokerRequestType.HEARTBEAT.getCode()) {
            return handleHeartbeat(request, brokerRequest);
        } else if (requestType == BrokerRequestType.ONLINE.getCode()) {
            return handleOnline(request, brokerRequest);
        } else if (requestType == BrokerRequestType.OFFLINE.getCode()) {
            return handleOffline(request, brokerRequest);
        }

        throw new RuntimeException("unsupported request type " + requestType);
    }

    private Datagram handleHeartbeat(final Datagram request, final BrokerRegisterRequest brokerRequest) {
        final String groupName = brokerRequest.getGroupName();
        final int brokerState = brokerRequest.getBrokerState();

        refreshHeartbeat(groupName);

        final BrokerGroup brokerGroupInCache = cachedMetaInfoManager.getBrokerGroup(groupName);
        String groupMaster = "";
        if (brokerGroupInCache == null || brokerState != brokerGroupInCache.getBrokerState().getCode()) {
            final BrokerGroup groupInStore = store.getBrokerGroup(groupName);
            if (groupInStore != null && groupInStore.getBrokerState().getCode() != brokerState) {
                store.updateBrokerGroup(groupName, BrokerState.codeOf(brokerState));
                groupMaster = groupInStore.getMaster();
            }
        } else {
            groupMaster = brokerGroupInCache.getMaster();
        }

        LOG.info("Broker heartbeat response, request:{}", brokerRequest);
        BrokerHeartBeatResponse resp = new BrokerHeartBeatResponse();
        resp.setMasterAddress(groupMaster);
        return RemotingBuilder.buildResponseDatagram(CommandCode.SUCCESS, request.getHeader(), out -> {
            BrokerHeartBeatResponseSerializer.serialize(resp, out);
        });
    }

    private Datagram handleSlaveHeartbeat(final Datagram request, final BrokerRegisterRequest brokerRequest) {
        final String groupName = brokerRequest.getGroupName();
        final int brokerState = brokerRequest.getBrokerState();

        LOG.info("Broker heartbeat response, request:{}", brokerRequest);
        Optional<BrokerMeta> brokerMetaOptional;
        if (brokerRequest.getBrokerRole() == BrokerRole.SLAVE.getCode()){
            brokerMetaOptional = brokerStore.queryByRole(groupName, BrokerRole.MASTER.getCode());
        } else if (brokerRequest.getBrokerRole() == BrokerRole.DELAY_SLAVE.getCode()){
            brokerMetaOptional = brokerStore.queryByRole(groupName, BrokerRole.DELAY_MASTER.getCode());
        } else {
            throw new RuntimeException("not a slave!" + brokerRequest.toString());
        }

        BrokerMeta brokerMeta = brokerMetaOptional.orElseThrow(() -> new RuntimeException("broker not existed"));

        BrokerHeartBeatResponse resp = new BrokerHeartBeatResponse();
        resp.setMasterAddress(brokerMeta.getIp() + ":" + brokerMeta.getSyncPort());
        LOG.info("心跳处理结束, meta:{}", brokerMeta);
        return RemotingBuilder.buildResponseDatagram(CommandCode.SUCCESS, request.getHeader(), out -> {
            BrokerHeartBeatResponseSerializer.serialize(resp, out);
        });
    }

    private Datagram handleOnline(final Datagram request, final BrokerRegisterRequest brokerRequest) {
        final String groupName = brokerRequest.getGroupName();
        final String brokerAddress = brokerRequest.getBrokerAddress();
        final BrokerGroupKind kind = BrokerRole.fromCode(brokerRequest.getBrokerRole()).getKind();

        refreshHeartbeat(groupName);

        store.insertOrUpdateBrokerGroup(groupName, kind, brokerAddress, BrokerState.RW);
        handleIPChange(brokerRequest);

        cachedMetaInfoManager.executeRefreshTask();
        LOG.info("Broker online success, request:{}", brokerRequest);
        return RemotingBuilder.buildEmptyResponseDatagram(CommandCode.SUCCESS, request.getHeader());
    }

    private void handleIPChange(final BrokerRegisterRequest brokerRequest){
        final String groupName = brokerRequest.getGroupName();
        final String brokerAddress = brokerRequest.getBrokerAddress();
        Optional<BrokerMeta> optionalBrokerMeta = brokerStore.queryByRole(groupName, brokerRequest.getBrokerRole());
        BrokerMeta brokerMeta = optionalBrokerMeta.orElseThrow(() -> new RuntimeException("unsupported request type "));
        BrokerMeta newBrokerMeta = new BrokerMeta(brokerMeta.getGroup(), brokerMeta.getRole(),
                brokerMeta.getHostname(), brokerAddress.substring(0, brokerAddress.indexOf(':')), brokerMeta.getServePort(), brokerMeta.getSyncPort());
        brokerStore.replaceBrokerByRole(brokerMeta, newBrokerMeta);
    }

    private Datagram handleOffline(final Datagram request, final BrokerRegisterRequest brokerRequest) {
        final String groupName = brokerRequest.getGroupName();
        final String brokerAddress = brokerRequest.getBrokerAddress();
        final BrokerGroupKind kind = BrokerRole.fromCode(brokerRequest.getBrokerRole()).getKind();

        store.insertOrUpdateBrokerGroup(groupName, kind, brokerAddress, BrokerState.NRW);
        cachedMetaInfoManager.executeRefreshTask();
        LOG.info("broker offline success, request:{}", brokerRequest);
        return RemotingBuilder.buildEmptyResponseDatagram(CommandCode.SUCCESS, request.getHeader());
    }

    private void refreshHeartbeat(String groupName) {
        heartbeatManager.cancel(groupName);
        final HeartbeatTimerTask heartbeatTimerTask = new HeartbeatTimerTask(store, groupName);
        final long timeoutMs = config.getLong("heartbeat.timeout.ms", HEARTBEAT_TIMEOUT_MS);
        heartbeatManager.refreshHeartbeat(groupName, heartbeatTimerTask, timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }

    private static class HeartbeatTimerTask implements TimerTask {
        private final Store store;
        private final String groupName;

        private HeartbeatTimerTask(final Store store, final String groupName) {
            this.store = store;
            this.groupName = groupName;
        }

        @Override
        public void run(Timeout timeout) {
            QMon.brokerDisconnectedCountInc(groupName);
            LOG.warn("broker group lost connection, groupName:{}", groupName);
            store.updateBrokerGroup(groupName, BrokerState.NRW);
        }
    }
}
