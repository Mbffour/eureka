/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.netflix.appinfo.AmazonInfo;
import com.netflix.appinfo.AmazonInfo.MetaDataKey;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.DataCenterInfo.Name;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.eureka.registry.rule.DownOrStartingRule;
import com.netflix.eureka.registry.rule.FirstMatchWinsCompositeRule;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.registry.rule.LeaseExistsRule;
import com.netflix.eureka.registry.rule.OverrideExistsRule;
import com.netflix.eureka.resources.CurrentRequestVersion;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.Version;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.resources.ASGResource.ASGStatus;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.annotations.DataSourceType;
import com.netflix.servo.monitor.Monitors;
import com.netflix.servo.monitor.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles replication of all operations to {@link AbstractInstanceRegistry} to peer
 * <em>Eureka</em> nodes to keep them all in sync.
 *
 * <p>
 * Primary operations that are replicated are the
 * <em>Registers,Renewals,Cancels,Expirations and Status Changes</em>
 * </p>
 *
 * <p>
 * When the eureka server starts up it tries to fetch all the registry
 * information from the peer eureka nodes.If for some reason this operation
 * fails, the server does not allow the user to get the registry information for
 * a period specified in
 * {@link com.netflix.eureka.EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}.
 * </p>
 *
 * <p>
 * One important thing to note about <em>renewals</em>.If the renewal drops more
 * than the specified threshold as specified in
 * {@link com.netflix.eureka.EurekaServerConfig#getRenewalPercentThreshold()} within a period of
 * {@link com.netflix.eureka.EurekaServerConfig#getRenewalThresholdUpdateIntervalMs()}, eureka
 * perceives this as a danger and stops expiring instances.
 * </p>
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@Singleton
public class PeerAwareInstanceRegistryImpl extends AbstractInstanceRegistry implements PeerAwareInstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PeerAwareInstanceRegistryImpl.class);

    private static final String US_EAST_1 = "us-east-1";
    private static final int PRIME_PEER_NODES_RETRY_MS = 30000;

    private long startupTime = 0;
    private boolean peerInstancesTransferEmptyOnStartup = true;

    public enum Action {
        Heartbeat, Register, Cancel, StatusUpdate, DeleteStatusOverride;

        private com.netflix.servo.monitor.Timer timer = Monitors.newTimer(this.name());

        public com.netflix.servo.monitor.Timer getTimer() {
            return this.timer;
        }
    }

    private static final Comparator<Application> APP_COMPARATOR = new Comparator<Application>() {
        public int compare(Application l, Application r) {
            return l.getName().compareTo(r.getName());
        }
    };

    private final MeasuredRate numberOfReplicationsLastMin;

    protected final EurekaClient eurekaClient;
    protected volatile PeerEurekaNodes peerEurekaNodes;

    private final InstanceStatusOverrideRule instanceStatusOverrideRule;

    private Timer timer = new Timer(
            "ReplicaAwareInstanceRegistry - RenewalThresholdUpdater", true);

    @Inject
    public PeerAwareInstanceRegistryImpl(
            EurekaServerConfig serverConfig,
            EurekaClientConfig clientConfig,
            ServerCodecs serverCodecs,
            EurekaClient eurekaClient
    ) {
        super(serverConfig, clientConfig, serverCodecs);
        this.eurekaClient = eurekaClient;

        /**
         * todo 60秒刷新一次 节点复制次数？
         */
        this.numberOfReplicationsLastMin = new MeasuredRate(1000 * 60 * 1);
        // We first check if the instance is STARTING or DOWN, then we check explicit overrides,
        // then we check the status of a potentially existing lease.

        /**
         * 我们首先检查实例是STARTING还是DOWN，然后检查显式覆盖，
         *
         * 然后我们检查可能存在的租约的状态。
         *
         *
         *  复合规则匹配
         */
        this.instanceStatusOverrideRule = new FirstMatchWinsCompositeRule(

                //STARTING/DOWM
                new DownOrStartingRule(),
                //应用实例覆盖状态  当存在覆盖状态( statusoverrides ) ，使用该状态
                new OverrideExistsRule(overriddenInstanceStatusMap),
                //UP OUT——OF——SERVICE
                //来自 Eureka-Client 的请求( 非 Eureka-Server 集群请求)，
                // 当 Eureka-Server 的实例状态存在，并且处于 UP 或则 OUT_OF_SERVICE ，
                // 保留当前状态。原因，禁止 Eureka-Client 主动在这两个状态之间切换。
                // 如果要切换，使用应用实例覆盖状态变更与删除接口
                new LeaseExistsRule());

        //AlwaysMatchInstanceStatusRule 使用 instanceInfo 的状态返回，以保证能匹配到状态
        //可以将 instanceInfo 理解成请求方的状态
    }

    @Override
    protected InstanceStatusOverrideRule getInstanceInfoOverrideRule() {
        return this.instanceStatusOverrideRule;
    }

    /**
     * 初始化节点
     * @param peerEurekaNodes
     * @throws Exception
     */
    @Override
    public void init(PeerEurekaNodes peerEurekaNodes) throws Exception {
        //60秒刷新一次节点复制
        this.numberOfReplicationsLastMin.start();

        this.peerEurekaNodes = peerEurekaNodes;

        // 注释：初始化 Eureka Server 响应缓存，默认缓存时间为30s
        initializedResponseCache();

        // 注释：定时任务，多久重置一下心跳阈值，900000 毫秒，即 15分钟 的间隔时间，会重置心跳阈值
        scheduleRenewalThresholdUpdateTask();

        // 注释：初始化远端注册
        initRemoteRegionRegistry();

        try {
            Monitors.registerObject(this);
        } catch (Throwable e) {
            logger.warn("Cannot register the JMX monitor for the InstanceRegistry :", e);
        }
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        try {
            DefaultMonitorRegistry.getInstance().unregister(Monitors.newObjectMonitor(this));
        } catch (Throwable t) {
            logger.error("Cannot shutdown monitor registry", t);
        }
        try {
            peerEurekaNodes.shutdown();
        } catch (Throwable t) {
            logger.error("Cannot shutdown ReplicaAwareInstanceRegistry", t);
        }
        numberOfReplicationsLastMin.stop();

        super.shutdown();
    }

    /**
     * Schedule the task that updates <em>renewal threshold</em> periodically.
     * The renewal threshold would be used to determine if the renewals drop
     * dramatically because of network partition and to protect expiring too
     * many instances at a time.
     *
     */
    private void scheduleRenewalThresholdUpdateTask() {
        timer.schedule(new TimerTask() {
                           @Override
                           public void run() {
                               updateRenewalThreshold();
                           }
                       }, serverConfig.getRenewalThresholdUpdateIntervalMs(),
                serverConfig.getRenewalThresholdUpdateIntervalMs());
    }

    /**
     * Populates the registry information from a peer eureka node. This
     * operation fails over to other nodes until the list is exhausted if the
     * communication fails.
     */
    @Override
    /**
     * 从集群的一个 Eureka-Server 节点获取初始注册信息
     */
    public int syncUp() {
        // Copy entire entry from neighboring DS node
        int count = 0;

        //失败重试次数
        for (int i = 0; ((i < serverConfig.getRegistrySyncRetries()) && (count == 0)); i++) {

            // 未读取到注册信息，sleep 等待
            if (i > 0) {
                try {
                    //失败睡眠时间
                    Thread.sleep(serverConfig.getRegistrySyncRetryWaitMs());
                } catch (InterruptedException e) {
                    logger.warn("Interrupted during registry transfer..");
                    break;
                }
            }

            // 获取注册信息
            Applications apps = eurekaClient.getApplications();
            for (Application app : apps.getRegisteredApplications()) {
                for (InstanceInfo instance : app.getInstances()) {
                    try {
                        if (isRegisterable(instance)) { // 判断是否能够注册

                            //注册应用实例到自身节点
                            register(instance,
                                    instance.getLeaseInfo().getDurationInSecs(),
                                    true);// 注册
                            count++;
                        }
                    } catch (Throwable t) {
                        logger.error("During DS init copy", t);
                    }
                }
            }
        }
        return count;
    }

    /**
     * 拉取服务信息
     * @param applicationInfoManager
     * @param count
     */
    @Override
    public void openForTraffic(ApplicationInfoManager applicationInfoManager, int count) {
        /**
         * 续订每30秒发生一次，它应该是2倍。
         */
        // Renewals happen every 30 seconds and for a minute it should be a factor of 2.
        this.expectedNumberOfClientsSendingRenews = count;

        /**
         * *2
         */
        updateRenewsPerMinThreshold();
        logger.info("Got {} instances from neighboring DS node", count);
        logger.info("Renew threshold is: {}", numberOfRenewsPerMinThreshold);
        this.startupTime = System.currentTimeMillis();
        if (count > 0) {
            this.peerInstancesTransferEmptyOnStartup = false;
        }
        DataCenterInfo.Name selfName = applicationInfoManager.getInfo().getDataCenterInfo().getName();
        boolean isAws = Name.Amazon == selfName;
        if (isAws && serverConfig.shouldPrimeAwsReplicaConnections()) {
            logger.info("Priming AWS connections for all replicas..");
            primeAwsReplicas(applicationInfoManager);
        }
        logger.info("Changing status to UP");
        // 注释：修改 Eureka Server 为上电状态，就是说设置 Eureka Server 已经处于活跃状态了，那就是意味着 EurekaServer 基本上说可以正常使用了；
        applicationInfoManager.setInstanceStatus(InstanceStatus.UP);


        /**
         * 初始化过期任务
         */
        super.postInit();
    }

    /**
     * Prime connections for Aws replicas.
     * <p>
     * Sometimes when the eureka servers comes up, AWS firewall may not allow
     * the network connections immediately. This will cause the outbound
     * connections to fail, but the inbound connections continue to work. What
     * this means is the clients would have switched to this node (after EIP
     * binding) and so the other eureka nodes will expire all instances that
     * have been switched because of the lack of outgoing heartbeats from this
     * instance.
     * </p>
     * <p>
     * The best protection in this scenario is to block and wait until we are
     * able to ping all eureka nodes successfully atleast once. Until then we
     * won't open up the traffic.
     * </p>
     */
    private void primeAwsReplicas(ApplicationInfoManager applicationInfoManager) {
        boolean areAllPeerNodesPrimed = false;
        while (!areAllPeerNodesPrimed) {
            String peerHostName = null;
            try {
                Application eurekaApps = this.getApplication(applicationInfoManager.getInfo().getAppName(), false);
                if (eurekaApps == null) {
                    areAllPeerNodesPrimed = true;
                    logger.info("No peers needed to prime.");
                    return;
                }
                for (PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
                    for (InstanceInfo peerInstanceInfo : eurekaApps.getInstances()) {
                        LeaseInfo leaseInfo = peerInstanceInfo.getLeaseInfo();
                        // If the lease is expired - do not worry about priming
                        if (System.currentTimeMillis() > (leaseInfo
                                .getRenewalTimestamp() + (leaseInfo
                                .getDurationInSecs() * 1000))
                                + (2 * 60 * 1000)) {
                            continue;
                        }
                        peerHostName = peerInstanceInfo.getHostName();
                        logger.info("Trying to send heartbeat for the eureka server at {} to make sure the " +
                                "network channels are open", peerHostName);
                        // Only try to contact the eureka nodes that are in this instance's registry - because
                        // the other instances may be legitimately down
                        if (peerHostName.equalsIgnoreCase(new URI(node.getServiceUrl()).getHost())) {
                            node.heartbeat(
                                    peerInstanceInfo.getAppName(),
                                    peerInstanceInfo.getId(),
                                    peerInstanceInfo,
                                    null,
                                    true);
                        }
                    }
                }
                areAllPeerNodesPrimed = true;
            } catch (Throwable e) {
                logger.error("Could not contact {}", peerHostName, e);
                try {
                    Thread.sleep(PRIME_PEER_NODES_RETRY_MS);
                } catch (InterruptedException e1) {
                    logger.warn("Interrupted while priming : ", e1);
                    areAllPeerNodesPrimed = true;
                }
            }
        }
    }

    /**
     * Checks to see if the registry access is allowed or the server is in a
     * situation where it does not all getting registry information. The server
     * does not return registry information for a period specified in
     * {@link EurekaServerConfig#getWaitTimeInMsWhenSyncEmpty()}, if it cannot
     * get the registry information from the peer eureka nodes at start up.
     *
     * @return false - if the instances count from a replica transfer returned
     *         zero and if the wait time has not elapsed, otherwise returns true
     */
    @Override
    /**
     * 判断 Eureka-Server 是否允许被 Eureka-Client 获取注册信息
     */
    public boolean shouldAllowAccess(boolean remoteRegionRequired) {

        //true 允许
        if (this.peerInstancesTransferEmptyOnStartup) {
            if (!(System.currentTimeMillis() > this.startupTime + serverConfig.getWaitTimeInMsWhenSyncEmpty())) {
                return false;
            }
        }
        if (remoteRegionRequired) {
            for (RemoteRegionRegistry remoteRegionRegistry : this.regionNameVSRemoteRegistry.values()) {
                if (!remoteRegionRegistry.isReadyForServingData()) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean shouldAllowAccess() {
        return shouldAllowAccess(true);
    }

    /**
     * @deprecated use {@link com.netflix.eureka.cluster.PeerEurekaNodes#getPeerEurekaNodes()} directly.
     *
     * Gets the list of peer eureka nodes which is the list to replicate
     * information to.
     *
     * @return the list of replica nodes.
     */
    @Deprecated
    public List<PeerEurekaNode> getReplicaNodes() {
        return Collections.unmodifiableList(peerEurekaNodes.getPeerEurekaNodes());
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#cancel(java.lang.String,
     * java.lang.String, long, boolean)
     */
    @Override
    public boolean cancel(final String appName, final String id,
                          final boolean isReplication) {

        //下线
        if (super.cancel(appName, id, isReplication)) {

            // Eureka-Server 复制
            replicateToPeers(Action.Cancel, appName, id, null, null, isReplication);

            // 减少 `numberOfRenewsPerMinThreshold` 、`expectedNumberOfRenewsPerMin`
            //自我保护机制相关
            synchronized (lock) {
                if (this.expectedNumberOfClientsSendingRenews > 0) {
                    // Since the client wants to cancel it, reduce the number of clients to send renews
                    this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews - 1;
                    updateRenewsPerMinThreshold();
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Registers the information about the {@link InstanceInfo} and replicates
     * this information to all peer eureka nodes. If this is replication event
     * from other replica nodes then it is not replicated.
     *
     * @param info
     *            the {@link InstanceInfo} to be registered and replicated.
     * @param isReplication
     *            true if this is a replication event from other replica nodes,
     *            false otherwise.
     */
    @Override
    public void register(final InstanceInfo info, final boolean isReplication) {
        // 注释：续约时间，默认时间是常量值 90 秒
        int leaseDuration = Lease.DEFAULT_DURATION_IN_SECS;

        // 注释：续约时间，当然也可以从配置文件中取出来，所以说续约时间值也是可以让我们自己自定义配置的
        if (info.getLeaseInfo() != null && info.getLeaseInfo().getDurationInSecs() > 0) {
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }
        // 注释：将注册方的信息写入 EurekaServer 的注册表，父类为　AbstractInstanceRegistry
        /**
         * AbstractInstanceRegistry#register
         */
        super.register(info, leaseDuration, isReplication);

        // 注释：EurekaServer 节点之间的数据同步，复制到其他Peer
        replicateToPeers(Action.Register, info.getAppName(), info.getId(), info, null, isReplication);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#renew(java.lang.String,
     * java.lang.String, long, boolean)
     */

    /**
     *  服务续约接口 同步
     * @param appName
     * @param id
     *            - unique id within appName
     * @param isReplication
     *            - whether this is a replicated entry from another ds node
     * @return
     */
    public boolean renew(final String appName, final String id, final boolean isReplication) {
        if (super.renew(appName, id, isReplication)) {
            /**
             * 同步到所有节点  是否来自复制节点
             */
            replicateToPeers(Action.Heartbeat, appName, id, null, null, isReplication);
            return true;
        }
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.netflix.eureka.registry.InstanceRegistry#statusUpdate(java.lang.String,
     * java.lang.String, com.netflix.appinfo.InstanceInfo.InstanceStatus,
     * java.lang.String, boolean)
     */

    /**
     * 调用 PeerAwareInstanceRegistryImpl#statusUpdate(...) 方法，更新应用实例覆盖状态
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param newStatus the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return
     */
    @Override
    public boolean statusUpdate(final String appName, final String id,
                                final InstanceStatus newStatus, String lastDirtyTimestamp,
                                final boolean isReplication) {
        if (super.statusUpdate(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {

            // Eureka-Server 集群同步
            replicateToPeers(Action.StatusUpdate, appName, id, null, newStatus, isReplication);
            return true;
        }
        return false;
    }

    /**
     * 删除覆盖状态
     * @param appName the application name of the instance.
     * @param id the unique identifier of the instance.
     * @param newStatus the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return
     */
    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {

        if (super.deleteStatusOverride(appName, id, newStatus, lastDirtyTimestamp, isReplication)) {
            replicateToPeers(Action.DeleteStatusOverride, appName, id, null, null, isReplication);
            // Eureka-Server 集群同步
            return true;
        }
        return false;
    }

    /**
     * Replicate the <em>ASG status</em> updates to peer eureka nodes. If this
     * event is a replication from other nodes, then it is not replicated to
     * other nodes.
     *
     * @param asgName the asg name for which the status needs to be replicated.
     * @param newStatus the {@link ASGStatus} information that needs to be replicated.
     * @param isReplication true if this is a replication event from other nodes, false otherwise.
     */
    @Override
    public void statusUpdate(final String asgName, final ASGStatus newStatus, final boolean isReplication) {
        // If this is replicated from an other node, do not try to replicate again.
        if (isReplication) {
            return;
        }
        for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
            replicateASGInfoToReplicaNodes(asgName, newStatus, node);

        }
    }

    @Override
    public boolean isLeaseExpirationEnabled() {
        if (!isSelfPreservationModeEnabled()) {
            // The self preservation mode is disabled, hence allowing the instances to expire.
            return true;
        }

        /**
         * 当每分钟心跳次数( renewsLastMin ) 小于 numberOfRenewsPerMinThreshold 时，
         * 并且开启自动保护模式开关( eureka.enableSelfPreservation = true )
         */
        return numberOfRenewsPerMinThreshold > 0 && getNumOfRenewsInLastMin() > numberOfRenewsPerMinThreshold;
    }

    /**
     * Checks to see if the self-preservation mode is enabled.
     *
     * <p>
     * The self-preservation mode is enabled if the expected number of renewals
     * per minute {@link #getNumOfRenewsInLastMin()} is lesser than the expected
     * threshold which is determined by {@link #getNumOfRenewsPerMinThreshold()}
     * . Eureka perceives this as a danger and stops expiring instances as this
     * is most likely because of a network event. The mode is disabled only when
     * the renewals get back to above the threshold or if the flag
     * {@link EurekaServerConfig#shouldEnableSelfPreservation()} is set to
     * false.
     * </p>
     *
     * @return true if the self-preservation mode is enabled, false otherwise.
     */
    @Override
    public boolean isSelfPreservationModeEnabled() {
        return serverConfig.shouldEnableSelfPreservation();
    }

    @Override
    public InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure) {
        // TODO Auto-generated method stub
        return null;
    }

    /**
     * Updates the <em>renewal threshold</em> based on the current number of
     * renewals. The threshold is a percentage as specified in
     * {@link EurekaServerConfig#getRenewalPercentThreshold()} of renewals
     * received per minute {@link #getNumOfRenewsInLastMin()}.
     */

    /**
     * 自我保护机锁  15 分钟 执行
     *
     * * 当计算如下参数时使用：
     * *  1. {@link #numberOfRenewsPerMinThreshold}
     * *  2. {@link #expectedNumberOfRenewsPerMin}
     */
    private void updateRenewalThreshold() {
        try {

            // 计算 应用实例数
            Applications apps = eurekaClient.getApplications();
            int count = 0;
            for (Application app : apps.getRegisteredApplications()) {
                for (InstanceInfo instance : app.getInstances()) {
                    if (this.isRegisterable(instance)) {
                        ++count;
                    }
                }
            }
            // 计算 expectedNumberOfRenewsPerMin 、 numberOfRenewsPerMinThreshold 参数
            synchronized (lock) {
                // Update threshold only if the threshold is greater than the
                // current expected threshold or if self preservation is disabled.

                /**
                 * 当开启自我保护机制时，应用实例每分钟最大心跳数( count * 2 ) 小于期望最小每分钟续租次数
                 * 不重新计算。如果重新计算，自动保护机制会每次定时执行后失效
                 */
                if ((count*2) > (serverConfig.getRenewalPercentThreshold() * expectedNumberOfClientsSendingRenews)
                        || (!this.isSelfPreservationModeEnabled())) {
                    this.expectedNumberOfClientsSendingRenews = count;
                    updateRenewsPerMinThreshold();
                }
            }
            logger.info("Current renewal threshold is : {}", numberOfRenewsPerMinThreshold);
        } catch (Throwable e) {
            logger.error("Cannot update renewal threshold", e);
        }
    }

    /**
     * Gets the list of all {@link Applications} from the registry in sorted
     * lexical order of {@link Application#getName()}.
     *
     * @return the list of {@link Applications} in lexical order.
     */
    @Override
    public List<Application> getSortedApplications() {
        List<Application> apps = new ArrayList<Application>(getApplications().getRegisteredApplications());
        Collections.sort(apps, APP_COMPARATOR);
        return apps;
    }

    /**
     * Gets the number of <em>renewals</em> in the last minute.
     *
     * @return a long value representing the number of <em>renewals</em> in the last minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfReplicationsInLastMin",
            description = "Number of total replications received in the last minute",
            type = com.netflix.servo.annotations.DataSourceType.GAUGE)
    public long getNumOfReplicationsInLastMin() {
        return numberOfReplicationsLastMin.getCount();
    }

    /**
     * Checks if the number of renewals is lesser than threshold.
     *
     * @return 0 if the renewals are greater than threshold, 1 otherwise.
     */
    @com.netflix.servo.annotations.Monitor(name = "isBelowRenewThreshold", description = "0 = false, 1 = true",
            type = com.netflix.servo.annotations.DataSourceType.GAUGE)
    @Override
    public int isBelowRenewThresold() {
        if ((getNumOfRenewsInLastMin() <= numberOfRenewsPerMinThreshold)
                &&
                ((this.startupTime > 0) && (System.currentTimeMillis() > this.startupTime + (serverConfig.getWaitTimeInMsWhenSyncEmpty())))) {
            return 1;
        } else {
            return 0;
        }
    }

    /**
     * Checks if an instance is registerable in this region. Instances from other regions are rejected.
     *
     * @param instanceInfo  th instance info information of the instance
     * @return true, if it can be registered in this server, false otherwise.
     */
    public boolean isRegisterable(InstanceInfo instanceInfo) {
        DataCenterInfo datacenterInfo = instanceInfo.getDataCenterInfo();
        String serverRegion = clientConfig.getRegion();
        if (AmazonInfo.class.isInstance(datacenterInfo)) {
            AmazonInfo info = AmazonInfo.class.cast(instanceInfo.getDataCenterInfo());
            String availabilityZone = info.get(MetaDataKey.availabilityZone);
            // Can be null for dev environments in non-AWS data center
            if (availabilityZone == null && US_EAST_1.equalsIgnoreCase(serverRegion)) {
                return true;
            } else if ((availabilityZone != null) && (availabilityZone.contains(serverRegion))) {
                // If in the same region as server, then consider it registerable
                return true;
            }
        }
        return true; // Everything non-amazon is registrable.
    }

    /**
     * Replicates all eureka actions to peer eureka nodes except for replication
     * traffic to this node.
     * 同步所有数据
     */
    private void replicateToPeers(Action action, String appName, String id,
                                  InstanceInfo info /* optional */,
                                  InstanceStatus newStatus /* optional */, boolean isReplication) {
        //开始时间
        Stopwatch tracer = action.getTimer().start();
        try {
            /**
             * 同步节点
             */
            if (isReplication) {

                /**
                 * 复制节点最后计数时间
                 */
                numberOfReplicationsLastMin.increment();
            }
            // If it is a replication already, do not replicate again as this will create a poison replication

            /**
             * 如果已经复制过了 就不再复制
             * 来自复制节点就不需要再复制传播了  重复调用
             */
            if (peerEurekaNodes == Collections.EMPTY_LIST || isReplication) {
                return;
            }

            // 遍历Eureka Server集群中的所有节点，进行复制操作
            for (final PeerEurekaNode node : peerEurekaNodes.getPeerEurekaNodes()) {
                // If the url represents this host, do not replicate to yourself.

                //如果网址代表此主机，请不要复制给自己。
                if (peerEurekaNodes.isThisMyUrl(node.getServiceUrl())) {
                    continue;
                }
                // 没有复制过，遍历Eureka Server集群中的node节点，依次操作，包括取消、注册、心跳、状态更新等。
                //   每当有注册请求，首先更新 EurekaServer 的注册表，然后再将信息同步到其它EurekaServer的节点上去；

                replicateInstanceActionsToPeers(action, appName, id, info, newStatus, node);
            }
        } finally {
            tracer.stop();
        }
    }

    /**
     * Replicates all instance changes to peer eureka nodes except for
     * replication traffic to this node.
     *  节点之间的复制状态操作
     */
    private void replicateInstanceActionsToPeers(Action action, String appName,
                                                 String id, InstanceInfo info, InstanceStatus newStatus,
                                                 PeerEurekaNode node) {
        try {

            /**
             * 集群节点直接发送http协议包
             */
            InstanceInfo infoFromRegistry = null;
            CurrentRequestVersion.set(Version.V2);
            switch (action) {
                case Cancel:
                    node.cancel(appName, id);
                    break;
                case Heartbeat:
                    InstanceStatus overriddenStatus = overriddenInstanceStatusMap.get(id);
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.heartbeat(appName, id, infoFromRegistry, overriddenStatus, false);
                    break;
                case Register:
                    node.register(info);
                    break;
                case StatusUpdate:
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.statusUpdate(appName, id, newStatus, infoFromRegistry);
                    break;
                case DeleteStatusOverride:
                    infoFromRegistry = getInstanceByAppAndId(appName, id, false);
                    node.deleteStatusOverride(appName, id, infoFromRegistry);
                    break;
            }
        } catch (Throwable t) {
            logger.error("Cannot replicate information to {} for action {}", node.getServiceUrl(), action.name(), t);
        }
    }

    /**
     * Replicates all ASG status changes to peer eureka nodes except for
     * replication traffic to this node.
     */
    private void replicateASGInfoToReplicaNodes(final String asgName,
                                                final ASGStatus newStatus, final PeerEurekaNode node) {
        CurrentRequestVersion.set(Version.V2);
        try {
            node.statusUpdate(asgName, newStatus);
        } catch (Throwable e) {
            logger.error("Cannot replicate ASG status information to {}", node.getServiceUrl(), e);
        }
    }

    @Override
    @com.netflix.servo.annotations.Monitor(name = "localRegistrySize",
            description = "Current registry size", type = DataSourceType.GAUGE)
    public long getLocalRegistrySize() {
        return super.getLocalRegistrySize();
    }
}
