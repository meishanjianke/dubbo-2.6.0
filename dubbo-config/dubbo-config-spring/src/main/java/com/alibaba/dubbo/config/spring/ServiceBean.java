/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.config.spring;

import com.alibaba.dubbo.config.ApplicationConfig;
import com.alibaba.dubbo.config.ModuleConfig;
import com.alibaba.dubbo.config.MonitorConfig;
import com.alibaba.dubbo.config.ProtocolConfig;
import com.alibaba.dubbo.config.ProviderConfig;
import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.ServiceConfig;
import com.alibaba.dubbo.config.annotation.Service;
import com.alibaba.dubbo.config.spring.extension.SpringExtensionFactory;

import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.AbstractApplicationContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ServiceFactoryBean
 *
 * @export
 */
public class ServiceBean<T> extends ServiceConfig<T> implements InitializingBean, DisposableBean, ApplicationContextAware, ApplicationListener<ContextRefreshedEvent>, BeanNameAware {

    private static final long serialVersionUID = 213195494150089726L;

    private static transient ApplicationContext SPRING_CONTEXT;

    private final transient Service service;

    private transient ApplicationContext applicationContext;

    private transient String beanName;

    private transient boolean supportedApplicationListener;

    public ServiceBean() {
        super();
        this.service = null;
    }

    public ServiceBean(Service service) {
        super(service);
        this.service = service;
    }

    public static ApplicationContext getSpringContext() {
        return SPRING_CONTEXT;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        SpringExtensionFactory.addApplicationContext(applicationContext);
        if (applicationContext != null) {
            SPRING_CONTEXT = applicationContext;
            try {
                Method method = applicationContext.getClass().getMethod("addApplicationListener", new Class<?>[]{ApplicationListener.class}); // backward compatibility to spring 2.0.1
                method.invoke(applicationContext, new Object[]{this});
                supportedApplicationListener = true;
            } catch (Throwable t) {
                if (applicationContext instanceof AbstractApplicationContext) {
                    try {
                        Method method = AbstractApplicationContext.class.getDeclaredMethod("addListener", new Class<?>[]{ApplicationListener.class}); // backward compatibility to spring 2.0.1
                        if (!method.isAccessible()) {
                            method.setAccessible(true);
                        }
                        method.invoke(applicationContext, new Object[]{this});
                        supportedApplicationListener = true;
                    } catch (Throwable t2) {
                    }
                }
            }
        }
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    /**
     * Gets associated {@link Service}
     *
     * @return associated {@link Service}
     */
    public Service getService() {
        return service;
    }

    /**
     * 当我们ioc容器整个刷新完成，也就是ioc容器里面所有对象都创建完成以后来回调方法
     * onApplicationEvent 是一个事件响应方法，该方法会在收到 Spring 上下文刷新事件后执行服务导出操作。
     * 这个方法首先会根据条件决定是否导出服务，比如有些服务设置了延时导出，那么此时就不应该在此处导出。还有一些服务已经被导出了，或者当前服务被取消导出了，
     * 此时也不能再次导出相关服务。注意这里的 isDelay 方法，这个方法字面意思是“是否延迟导出服务”，返回 true 表示延迟导出，false 表示不延迟导出。
     * 但是该方法真实意思却并非如此，当方法返回 true 时，表示无需延迟导出。返回 false 时，表示需要延迟导出。与字面意思恰恰相反，这个需要大家注意一下。
     * @param event
     */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // 是否有延迟导出 && 是否已导出 && 是不是已被取消导出
        if (isDelay() && !isExported() && !isUnexported()) {
            if (logger.isInfoEnabled()) {
                logger.info("The service ready on spring started. service: " + getInterface());
            }
            // 导出服务
            export();
        }
    }

    /**
     * 暂时忽略 supportedApplicationListener 这个条件，当 delay 为空，或者等于-1时，该方法返回 true，而不是 false。这个方法的返回值让人有点困惑。该方法目前已被重构，详细请参考
     * dubbo #2686。
     *
     * 现在解释一下 supportedApplicationListener 变量含义，该变量用于表示当前的 Spring 容器是否支持 ApplicationListener，这个值初始为 false。在 Spring
     * 容器将自己设置到 ServiceBean 中时，ServiceBean 的 setApplicationContext 方法会检测 Spring 容器是否支持 ApplicationListener。若支持，则将
     * supportedApplicationListener 置为 true。ServiceBean 是 Dubbo 与 Spring 框架进行整合的关键，可以看做是两个框架之间的桥梁。具有同样作用的类还有
     * ReferenceBean。
     * @return
     */
    private boolean isDelay() {
        // 获取 delay
        Integer delay = getDelay();
        ProviderConfig provider = getProvider();
        if (delay == null && provider != null) {
            // 如果前面获取的 delay 为空，这里继续获取
            delay = provider.getDelay();
        }
        // 判断 delay 是否为空，或者等于 -1
        return supportedApplicationListener && (delay == null || delay == -1);
    }

    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public void afterPropertiesSet() throws Exception {
        // 判断是否解析过 provider 配置，解析过则不再解析
        if (getProvider() == null) {
            // 获取所有 ProviderConfig 类型的 bean
            Map<String, ProviderConfig> providerConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProviderConfig.class, false, false);
            if (providerConfigMap != null && providerConfigMap.size() > 0) {
                // 获取所有 ProtocolConfig 类型的 bean
                Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
                // 向后兼容，判断是否 protocol 为空并且 provider 不为空
                if ((protocolConfigMap == null || protocolConfigMap.size() == 0)
                        && providerConfigMap.size() > 1) { // backward compatibility
                    List<ProviderConfig> providerConfigs = new ArrayList<ProviderConfig>();
                    for (ProviderConfig config : providerConfigMap.values()) {
                        if (config.isDefault() != null && config.isDefault().booleanValue()) {
                            // 添加默认的 ProviderConfig
                            providerConfigs.add(config);
                        }
                    }
                    if (providerConfigs.size() > 0) {
                        // 将 ProviderConfig 转换为 ProtocolConfig 赋值给 protocols 成员属性
                        setProviders(providerConfigs);
                    }
                } else {
                    ProviderConfig providerConfig = null;
                    for (ProviderConfig config : providerConfigMap.values()) {
                        if (config.isDefault() == null || config.isDefault().booleanValue()) {
                            // 默认的 ProviderConfig 只能有一个
                            if (providerConfig != null) {
                                throw new IllegalStateException("Duplicate provider configs: " + providerConfig + " and " + config);
                            }
                            providerConfig = config;
                        }
                    }
                    if (providerConfig != null) {
                        // 设置 provider 配置
                        setProvider(providerConfig);
                    }
                }
            }
        }
        // 判断是否解析过 application 配置，解析过则不再解析
        if (getApplication() == null
                && (getProvider() == null || getProvider().getApplication() == null)) {
            // 获取所有 ApplicationConfig 类型的 bean
            Map<String, ApplicationConfig> applicationConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ApplicationConfig.class, false, false);
            if (applicationConfigMap != null && applicationConfigMap.size() > 0) {
                ApplicationConfig applicationConfig = null;
                for (ApplicationConfig config : applicationConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        // 默认的 ApplicationConfig 只能有一个
                        if (applicationConfig != null) {
                            throw new IllegalStateException("Duplicate application configs: " + applicationConfig + " and " + config);
                        }
                        applicationConfig = config;
                    }
                }
                if (applicationConfig != null) {
                    // 设置 application 配置
                    setApplication(applicationConfig);
                }
            }
        }
        // 判断是否解析过 module 配置，解析过则不再解析
        if (getModule() == null
                && (getProvider() == null || getProvider().getModule() == null)) {
            // 获取所有 ModuleConfig 类型的 bean
            Map<String, ModuleConfig> moduleConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ModuleConfig.class, false, false);
            if (moduleConfigMap != null && moduleConfigMap.size() > 0) {
                ModuleConfig moduleConfig = null;
                for (ModuleConfig config : moduleConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        // 默认的 ModuleConfig 只能有一个
                        if (moduleConfig != null) {
                            throw new IllegalStateException("Duplicate module configs: " + moduleConfig + " and " + config);
                        }
                        moduleConfig = config;
                    }
                }
                if (moduleConfig != null) {
                    // 设置 module 配置
                    setModule(moduleConfig);
                }
            }
        }
        // 判断是否解析过 registry 配置，解析过则不再解析
        if ((getRegistries() == null || getRegistries().size() == 0)
                && (getProvider() == null || getProvider().getRegistries() == null || getProvider().getRegistries().size() == 0)
                && (getApplication() == null || getApplication().getRegistries() == null || getApplication().getRegistries().size() == 0)) {
            // 获取所有 RegistryConfig 类型的 bean
            Map<String, RegistryConfig> registryConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, RegistryConfig.class, false, false);
            if (registryConfigMap != null && registryConfigMap.size() > 0) {
                List<RegistryConfig> registryConfigs = new ArrayList<RegistryConfig>();
                for (RegistryConfig config : registryConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        // 添加 RegistryConfig 配置
                        registryConfigs.add(config);
                    }
                }
                if (registryConfigs != null && registryConfigs.size() > 0) {
                    // 设置 registry 配置
                    super.setRegistries(registryConfigs);
                }
            }
        }
        // 判断是否解析过 monitor 配置，解析过则不再解析
        if (getMonitor() == null
                && (getProvider() == null || getProvider().getMonitor() == null)
                && (getApplication() == null || getApplication().getMonitor() == null)) {
            // 获取所有 MonitorConfig 类型的 bean
            Map<String, MonitorConfig> monitorConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, MonitorConfig.class, false, false);
            if (monitorConfigMap != null && monitorConfigMap.size() > 0) {
                MonitorConfig monitorConfig = null;
                for (MonitorConfig config : monitorConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        // 默认的 MonitorConfig 只能有一个
                        if (monitorConfig != null) {
                            throw new IllegalStateException("Duplicate monitor configs: " + monitorConfig + " and " + config);
                        }
                        monitorConfig = config;
                    }
                }
                if (monitorConfig != null) {
                    // 设置 monitor 配置
                    setMonitor(monitorConfig);
                }
            }
        }
        // 判断是否解析过 protocol 配置，解析过则不再解析
        if ((getProtocols() == null || getProtocols().size() == 0)
                && (getProvider() == null || getProvider().getProtocols() == null || getProvider().getProtocols().size() == 0)) {
            // 获取所有 ProtocolConfig 类型的bean
            Map<String, ProtocolConfig> protocolConfigMap = applicationContext == null ? null : BeanFactoryUtils.beansOfTypeIncludingAncestors(applicationContext, ProtocolConfig.class, false, false);
            if (protocolConfigMap != null && protocolConfigMap.size() > 0) {
                List<ProtocolConfig> protocolConfigs = new ArrayList<ProtocolConfig>();
                for (ProtocolConfig config : protocolConfigMap.values()) {
                    if (config.isDefault() == null || config.isDefault().booleanValue()) {
                        // 添加 ProtocolConfig 配置
                        protocolConfigs.add(config);
                    }
                }
                if (protocolConfigs != null && protocolConfigs.size() > 0) {
                    // 设置 protocol 配置
                    super.setProtocols(protocolConfigs);
                }
            }
        }
        // 判断是否解析过 path 配置，解析过则不再解析
        if (getPath() == null || getPath().length() == 0) {
            // interface 就是我们配置的 interface
            if (beanName != null && beanName.length() > 0
                    && getInterface() != null && getInterface().length() > 0
                    && beanName.startsWith(getInterface())) {
                // 检查 beanName 的格式（长度、正则）并设置为 path
                setPath(beanName);
            }
        }
        if (!isDelay()) {
            /* 没有设置延迟暴露则直接暴露 */
            export();
        }
    }

    @Override
    public void destroy() throws Exception {
        unexport();
    }

    // merged from dubbox
    @Override
    protected Class getServiceClass(T ref) {
        if (AopUtils.isAopProxy(ref)) {
            return AopUtils.getTargetClass(ref);
        }
        return super.getServiceClass(ref);
    }
}