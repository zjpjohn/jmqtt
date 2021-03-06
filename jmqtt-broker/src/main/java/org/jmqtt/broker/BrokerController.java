package org.jmqtt.broker;

import io.netty.handler.codec.mqtt.MqttMessageType;
import org.jmqtt.broker.client.ClientLifeCycleHookService;
import org.jmqtt.broker.dispatcher.DefaultDispatcherMessage;
import org.jmqtt.common.config.StoreConfig;
import org.jmqtt.remoting.netty.ChannelEventListener;
import org.jmqtt.store.*;
import org.jmqtt.store.memory.*;
import org.jmqtt.broker.dispatcher.MessageDispatcher;
import org.jmqtt.broker.processor.*;
import org.jmqtt.broker.subscribe.DefaultSubscriptionTreeMatcher;
import org.jmqtt.broker.subscribe.SubscriptionMatcher;
import org.jmqtt.common.config.BrokerConfig;
import org.jmqtt.common.config.NettyConfig;
import org.jmqtt.common.helper.MixAll;
import org.jmqtt.common.helper.RejectHandler;
import org.jmqtt.common.helper.ThreadFactoryImpl;
import org.jmqtt.common.log.LoggerName;
import org.jmqtt.remoting.netty.NettyRemotingServer;
import org.jmqtt.remoting.netty.RequestProcessor;
import org.jmqtt.store.redis.RedisMqttStore;
import org.jmqtt.store.rocksdb.RocksdbMqttStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class BrokerController {

    private static final Logger log = LoggerFactory.getLogger(LoggerName.BROKER);

    private BrokerConfig brokerConfig;
    private NettyConfig nettyConfig;
    private StoreConfig storeConfig;
    private ExecutorService connectExecutor;
    private ExecutorService pubExecutor;
    private ExecutorService subExecutor;
    private ExecutorService pingExecutor;
    private LinkedBlockingQueue connectQueue;
    private LinkedBlockingQueue pubQueue;
    private LinkedBlockingQueue subQueue;
    private LinkedBlockingQueue pingQueue;
    private ChannelEventListener channelEventListener;
    private NettyRemotingServer remotingServer;
    private MessageDispatcher messageDispatcher;
    private FlowMessageStore flowMessageStore;
    private SubscriptionMatcher subscriptionMatcher;
    private WillMessageStore willMessageStore;
    private RetainMessageStore retainMessageStore;
    private OfflineMessageStore offlineMessageStore;
    private SubscriptionStore subscriptionStore;
    private SessionStore sessionStore;
    private AbstractMqttStore abstractMqttStore;



    public BrokerController(BrokerConfig brokerConfig, NettyConfig nettyConfig,StoreConfig storeConfig){
        this.brokerConfig = brokerConfig;
        this.nettyConfig = nettyConfig;
        this.storeConfig = storeConfig;

        this.connectQueue = new LinkedBlockingQueue(100000);
        this.pubQueue = new LinkedBlockingQueue(100000);
        this.subQueue = new LinkedBlockingQueue(100000);
        this.pingQueue = new LinkedBlockingQueue(10000);

        {//store pluggable
            switch (storeConfig.getStoreType()){
                case 1:
                    this.abstractMqttStore = new RocksdbMqttStore(storeConfig);
                    break;
                case 2:
                    this.abstractMqttStore = new RedisMqttStore(storeConfig);
                    break;
                default:
                    this.abstractMqttStore = new DefaultMqttStore();
                break;
            }
            try {
                this.abstractMqttStore.init();
            } catch (Exception e) {
                System.out.println("Init Store failure,exception=" + e);
                e.printStackTrace();
            }
            this.flowMessageStore = this.abstractMqttStore.getFlowMessageStore();
            this.willMessageStore = this.abstractMqttStore.getWillMessageStore();
            this.retainMessageStore = this.abstractMqttStore.getRetainMessageStore();
            this.offlineMessageStore = this.abstractMqttStore.getOfflineMessageStore();
            this.subscriptionStore = this.abstractMqttStore.getSubscriptionStore();
            this.sessionStore = this.abstractMqttStore.getSessionStore();
         }
        this.subscriptionMatcher = new DefaultSubscriptionTreeMatcher();
        this.messageDispatcher = new DefaultDispatcherMessage(brokerConfig.getPollThreadNum(), subscriptionMatcher, flowMessageStore,offlineMessageStore);

        this.channelEventListener = new ClientLifeCycleHookService(willMessageStore,messageDispatcher);
        this.remotingServer = new NettyRemotingServer(nettyConfig,channelEventListener);

        int coreThreadNum = Runtime.getRuntime().availableProcessors();
        this.connectExecutor = new ThreadPoolExecutor(coreThreadNum*2,
                coreThreadNum*2,
                60000,
                TimeUnit.MILLISECONDS,
                connectQueue,
                new ThreadFactoryImpl("ConnectThread"),
                new RejectHandler("connect",100000));
        this.pubExecutor = new ThreadPoolExecutor(coreThreadNum*2,
                coreThreadNum*2,
                60000,
                TimeUnit.MILLISECONDS,
                pubQueue,
                new ThreadFactoryImpl("PubThread"),
                new RejectHandler("pub",100000));
        this.subExecutor = new ThreadPoolExecutor(coreThreadNum*2,
                coreThreadNum*2,
                60000,
                TimeUnit.MILLISECONDS,
                subQueue,
                new ThreadFactoryImpl("SubThread"),
                new RejectHandler("sub",100000));
        this.pingExecutor = new ThreadPoolExecutor(coreThreadNum,
                coreThreadNum,
                60000,
                TimeUnit.MILLISECONDS,
                pingQueue,
                new ThreadFactoryImpl("PingThread"),
                new RejectHandler("heartbeat",100000));

    }


    public void start(){

        MixAll.printProperties(log,brokerConfig);
        MixAll.printProperties(log,nettyConfig);

        {//init and register processor
            RequestProcessor connectProcessor = new ConnectProcessor(this);
            RequestProcessor disconnectProcessor = new DisconnectProcessor(this);
            RequestProcessor pingProcessor = new PingProcessor();
            RequestProcessor publishProcessor = new PublishProcessor(messageDispatcher, flowMessageStore,retainMessageStore);
            RequestProcessor pubRelProcessor = new PubRelProcessor(messageDispatcher, flowMessageStore,retainMessageStore);
            RequestProcessor subscribeProcessor = new SubscribeProcessor(subscriptionMatcher,retainMessageStore,flowMessageStore,subscriptionStore);
            RequestProcessor unSubscribeProcessor = new UnSubscribeProcessor(subscriptionMatcher,subscriptionStore);
            RequestProcessor pubRecProcessor = new PubRecProcessor(flowMessageStore);
            RequestProcessor pubCompProcessor = new PubCompProcessor(flowMessageStore);

            this.remotingServer.registerProcessor(MqttMessageType.CONNECT,connectProcessor,connectExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.DISCONNECT,disconnectProcessor,connectExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PINGREQ,pingProcessor,pingExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PUBLISH,publishProcessor,pubExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PUBREL,pubRelProcessor,pubExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.SUBSCRIBE,subscribeProcessor,subExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.UNSUBSCRIBE,unSubscribeProcessor,subExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PUBREC,pubRecProcessor,subExecutor);
            this.remotingServer.registerProcessor(MqttMessageType.PUBCOMP,pubCompProcessor,subExecutor);
        }

        this.remotingServer.start();
        log.info("JMqtt Server start success and version = {}",brokerConfig.getVersion());
    }

    public void shutdown(){
        this.remotingServer.shutdown();
        this.connectExecutor.shutdown();
        this.pubExecutor.shutdown();
        this.subExecutor.shutdown();
        this.pingExecutor.shutdown();
        this.messageDispatcher.shutdown();
    }

    public BrokerConfig getBrokerConfig() {
        return brokerConfig;
    }

    public NettyConfig getNettyConfig() {
        return nettyConfig;
    }

    public ExecutorService getConnectExecutor() {
        return connectExecutor;
    }

    public ExecutorService getPubExecutor() {
        return pubExecutor;
    }

    public ExecutorService getSubExecutor() {
        return subExecutor;
    }

    public ExecutorService getPingExecutor() {
        return pingExecutor;
    }

    public LinkedBlockingQueue getConnectQueue() {
        return connectQueue;
    }

    public LinkedBlockingQueue getPubQueue() {
        return pubQueue;
    }

    public LinkedBlockingQueue getSubQueue() {
        return subQueue;
    }

    public LinkedBlockingQueue getPingQueue() {
        return pingQueue;
    }

    public NettyRemotingServer getRemotingServer() {
        return remotingServer;
    }

    public MessageDispatcher getMessageDispatcher() {
        return messageDispatcher;
    }

    public FlowMessageStore getFlowMessageStore() {
        return flowMessageStore;
    }

    public SubscriptionMatcher getSubscriptionMatcher() {
        return subscriptionMatcher;
    }

    public WillMessageStore getWillMessageStore() {
        return willMessageStore;
    }

    public RetainMessageStore getRetainMessageStore() {
        return retainMessageStore;
    }

    public OfflineMessageStore getOfflineMessageStore() {
        return offlineMessageStore;
    }

    public SubscriptionStore getSubscriptionStore() {
        return subscriptionStore;
    }

    public SessionStore getSessionStore() {
        return sessionStore;
    }
}
