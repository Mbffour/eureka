package com.netflix;

public class TestDemo {
    /**
     * 1 EurekaServerConfig
     * 2 DefaultEurekaServerConfig
     * 3 EurekaBootStrap
     */


    /**
     *
     * LeaseManager-》InstanceRegistry-》AbstractInstanceRegistry-》
     *                                                          -》PeerAwareInstanceRegistryImpl
     *                                -》 PeerAwareInstanceRegistry
     */
    /**
     * Eureka-Server 过滤器( javax.servlet.Filter ) 顺序如下：
     *
     * StatusFilter
     * ServerRequestAuthFilter
     * RateLimitingFilter
     * GzipEncodingEnforcingFilter
     * ServletContainer
     */


    /**
     * Eureka-Client 向 Eureka-Server 发起注册应用实例需要符合如下条件：
     *
     * 配置 eureka.registration.enabled = true，Eureka-Client 向 Eureka-Server 发起注册应用实例的开关。
     * InstanceInfo 在 Eureka-Client 和 Eureka-Server 数据不一致。
     * 每次 InstanceInfo 发生属性变化时，标记 isInstanceInfoDirty 属性为 true，表示 InstanceInfo 在 Eureka-Client 和 Eureka-Server 数据不一致，需要注册。另外，InstanceInfo 刚被创建时，在 Eureka-Server 不存在，也会被注册。
     *
     * 当符合条件时，InstanceInfo 不会立即向 Eureka-Server 注册，而是后台线程定时注册。
     *
     * 当 InstanceInfo 的状态( status ) 属性发生变化时，并且配置 eureka.shouldOnDemandUpdateStatusChange = true 时，立即向 Eureka-Server 注册。因为状态属性非常重要，一般情况下建议开启，当然默认情况也是开启的。
     */


    /**
     * 计算公式如下：
     *
     * expectedNumberOfRenewsPerMin = 当前注册的应用实例数 x 2
     * numberOfRenewsPerMinThreshold = expectedNumberOfRenewsPerMin * 续租百分比( eureka.renewalPercentThreshold )
     * 为什么乘以 2
     *
     * 默认情况下，注册的应用实例每半分钟续租一次，那么一分钟心跳两次，因此 x 2 。
     *
     * 这块会有一些硬编码的情况，因此不太建议修改应用实例的续租频率。
     *
     * 为什么乘以续租百分比
     *
     * 低于这个百分比，意味着开启自我保护机制。
     *
     * 默认情况下，eureka.renewalPercentThreshold = 0.85 。
     *
     * 如果你真的调整了续租频率，可以等比去续租百分比，以保证合适的触发自我保护机制的阀值。另外，你需要注意，续租频率是 Client 级别，续租百分比是 Server 级别。
     */


    /**
     * 三层队列的好处：
     *
     * 接收队列，避免处理任务的阻塞等待。
     * 接收线程( Runner )合并任务，将相同任务编号( 是的，任务是带有编号的 )的任务合并，只执行一次。
     * Eureka-Server 为集群同步提供批量操作多个应用实例的接口，一个批量任务可以一次调度接口完成，避免多次调用的开销。当然，这样做的前提是合并任务，这也导致 Eureka-Server 集群之间对应用实例的注册和下线带来更大的延迟。毕竟，Eureka 是在 CAP 之间，选择了 AP
     */
}
