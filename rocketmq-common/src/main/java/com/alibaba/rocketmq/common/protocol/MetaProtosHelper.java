/**
 * $Id: MetaProtosHelper.java 1835 2013-05-16 02:00:50Z shijia.wxr $
 */
package com.alibaba.rocketmq.common.protocol;

import com.alibaba.rocketmq.common.protocol.MQProtos.MQRequestCode;
import com.alibaba.rocketmq.common.protocol.header.namesrv.RegisterBrokerRequestHeader;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import com.alibaba.rocketmq.remoting.exception.RemotingConnectException;
import com.alibaba.rocketmq.remoting.exception.RemotingSendRequestException;
import com.alibaba.rocketmq.remoting.exception.RemotingTimeoutException;
import com.alibaba.rocketmq.remoting.protocol.RemotingCommand;
import com.alibaba.rocketmq.remoting.protocol.RemotingProtos.ResponseCode;


/**
 * Э�鸨����
 * 
 * @author vintage.wang@gmail.com shijia.wxr@taobao.com
 * 
 */
public class MetaProtosHelper {
    /**
     * ��Broker��ַע�ᵽName Server
     */
    public static boolean registerBrokerToNameServer(final String nsaddr, final String brokerAddr,
            final long timeoutMillis) {
        RegisterBrokerRequestHeader requestHeader = new RegisterBrokerRequestHeader();
        requestHeader.setBrokerAddr(brokerAddr);

        RemotingCommand request =
                RemotingCommand.createRequestCommand(MQRequestCode.REGISTER_BROKER_VALUE, requestHeader);

        try {
            RemotingCommand response = RemotingHelper.invokeSync(nsaddr, request, timeoutMillis);
            if (response != null) {
                return ResponseCode.SUCCESS_VALUE == response.getCode();
            }
        }
        catch (RemotingConnectException e) {
            e.printStackTrace();
        }
        catch (RemotingSendRequestException e) {
            e.printStackTrace();
        }
        catch (RemotingTimeoutException e) {
            e.printStackTrace();
        }
        catch (InterruptedException e) {
            e.printStackTrace();
        }

        return false;
    }
}