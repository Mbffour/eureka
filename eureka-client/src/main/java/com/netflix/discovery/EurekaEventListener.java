package com.netflix.discovery;

/**
 * Listener for receiving {@link EurekaClient} events such as {@link StatusChangeEvent}.  Register
 * a listener by calling {@link EurekaClient#registerEventListener(EurekaEventListener)}
 */


/**
 * 你可以实现自定义的事件监听器监听 CacheRefreshedEvent 事件，以达到持久化最新的注册信息到存储器( 例如，本地文件 )，
 * 通过这样的方式，配合实现 BackupRegistry 接口读取存储器。BackupRegistry 接口调用如下：
 */
public interface EurekaEventListener {
    /**
     * Notification of an event within the {@link EurekaClient}.  
     * 
     * {@link EurekaEventListener#onEvent} is called from the context of an internal eureka thread 
     * and must therefore return as quickly as possible without blocking.
     * 
     * @param event
     */
    public void onEvent(EurekaEvent event);
}
