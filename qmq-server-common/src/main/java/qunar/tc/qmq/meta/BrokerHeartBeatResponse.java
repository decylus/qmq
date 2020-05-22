package qunar.tc.qmq.meta;

/**
 * @author wangjiawei
 * @date 2020/3/13
 **/
public class BrokerHeartBeatResponse {

    private String masterAddress;

    public String getMasterAddress() {
        return masterAddress;
    }

    public void setMasterAddress(String masterAddress) {
        this.masterAddress = masterAddress;
    }
}
