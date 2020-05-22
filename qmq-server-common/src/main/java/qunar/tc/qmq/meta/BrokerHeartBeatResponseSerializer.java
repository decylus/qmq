package qunar.tc.qmq.meta;

import io.netty.buffer.ByteBuf;
import qunar.tc.qmq.utils.PayloadHolderUtils;

/**
 * @author wangjiawei
 * @date 2020/3/13
 **/
public class BrokerHeartBeatResponseSerializer {

    public static void serialize(BrokerHeartBeatResponse response, ByteBuf out) {
        PayloadHolderUtils.writeString(response.getMasterAddress(), out);
    }

    public static BrokerHeartBeatResponse deSerialize(ByteBuf out) {
        BrokerHeartBeatResponse response = new BrokerHeartBeatResponse();
        response.setMasterAddress(PayloadHolderUtils.readString(out));
        return response;
    }
}
