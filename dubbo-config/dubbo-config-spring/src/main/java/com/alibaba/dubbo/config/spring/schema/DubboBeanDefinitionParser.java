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
package com.alibaba.dubbo.config.spring.schema;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ReflectUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.config.*;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.rpc.Protocol;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.ManagedMap;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AbstractBeanDefinitionParser
 *
 * @export
 */
public class DubboBeanDefinitionParser implements BeanDefinitionParser {

    private static final Logger logger = LoggerFactory.getLogger(DubboBeanDefinitionParser.class);
    private static final Pattern GROUP_AND_VERION = Pattern.compile("^[\\-.0-9_a-zA-Z]+(\\:[\\-.0-9_a-zA-Z]+)?$");
    private final Class<?> beanClass;
    private final boolean required;

    public DubboBeanDefinitionParser(Class<?> beanClass, boolean required) {
        this.beanClass = beanClass;
        this.required = required;
    }

    @SuppressWarnings("unchecked")
    private static BeanDefinition parse(Element element, ParserContext parserContext, Class<?> beanClass, boolean required) {
        // 创建一个保存目标bean也就是对应的Config对象，如ApplicationConfig，RegistryConfig等的
        // BeanDefinition对象，最终其会注册到BeanFactoryRegistry中
        RootBeanDefinition beanDefinition = new RootBeanDefinition();
        beanDefinition.setBeanClass(beanClass);// 根据构造方法传入的类型设置beanClass
        beanDefinition.setLazyInit(false);// 设置懒加载为false
        // 这里会尝试获取当前标签的id值，如果当前标签不存在id值，则会根据以下策略来为其生成一个bean name：
        // 1. 获取其name属性，将其作为当前bean的名称；
        // 2. 如果name属性不存在，则获取其interface属性，将其作为bean的名称，这里如果beanClass
        //    是ProtocolConfig，则直接以dubbo作为其名称，这是因为ProtocolConfig中没有interface属性；
        // 3. 如果还是无法获取到名称，则直接以beanClass的名称作为其名称；
        // 4. 到这里，也就能保证一定会获取到一个名称，但是很有可能该名称在当前spring容器中已经使用过了，
        //    那么这里会判断当前容器中是否包含该名称，如果包含，则在一个无限循环中在其名称后加一个数字，
        //    最终一定能够保证生成的名称是唯一的
        String id = element.getAttribute("id");
        // 如果id属性为空，并且构造方法传入的required为true
        if ((id == null || id.length() == 0) && required) {
            // 生成的beanName默认为name属性值
            String generatedBeanName = element.getAttribute("name");
            // 如果name属性为空
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                if (ProtocolConfig.class.equals(beanClass)) {
                    // 如果解析的是<dubbo:protocol/>标签，设置beanName为dubbo
                    generatedBeanName = "dubbo";
                } else {
                    // 否则beanName赋值为interface属性值
                    generatedBeanName = element.getAttribute("interface");
                }
            }
            if (generatedBeanName == null || generatedBeanName.length() == 0) {
                // 如果beanName还是为空，则将其设置为beanClass的名称
                generatedBeanName = beanClass.getName();
            }
            id = generatedBeanName;
            int counter = 2;
            // 循环判断如果当前Spring上下文中包含当前id，则将id拼接递增数字后缀
            while (parserContext.getRegistry().containsBeanDefinition(id)) {
                id = generatedBeanName + (counter++);
            }
        }
        if (id != null && id.length() > 0) {
            // 如果到这里判断如果当前Spring上下文中包含当前bean id，则抛出bean id冲突的异常
            if (parserContext.getRegistry().containsBeanDefinition(id)) {
                throw new IllegalStateException("Duplicate spring bean id " + id);
            }
            // 注册BeanDefinition
            parserContext.getRegistry().registerBeanDefinition(id, beanDefinition);
            // 添加id属性值
            beanDefinition.getPropertyValues().addPropertyValue("id", id);
        }
        // <dubbo:protocol/>标签
        // 这里判断当前注册的beanClass是否为ProtocolConfig，如果是，则在当前BeanDefinitionRegistry
        // 中找到所有的包含这样一种属性的BeanDefinition，该属性名为protocol，属性值为ProtocolConfig
        // 类型，如果找到了，则将当前生成的ProtocolConfig的属性注入到这些找到的BeanDefinition中
        if (ProtocolConfig.class.equals(beanClass)) {
            for (String name : parserContext.getRegistry().getBeanDefinitionNames()) {
                BeanDefinition definition = parserContext.getRegistry().getBeanDefinition(name);
                PropertyValue property = definition.getPropertyValues().getPropertyValue("protocol");
                // 遍历所有的BeanDefinition，判断是否有protocol属性
                if (property != null) {
                    Object value = property.getValue();
                    if (value instanceof ProtocolConfig && id.equals(((ProtocolConfig) value).getName())) {
                        // 如果有并且是ProtocolConfig类型则为其添加对当前bean id的依赖
                        definition.getPropertyValues().addPropertyValue("protocol", new RuntimeBeanReference(id));
                    }
                }
            }
        // <dubbo:service/>标签
        // 如果当前beanClass是ServiceBean，这种bean对应的标签是<dubbo:service/>，这里会获取该标签
        // 中的class属性值，并以该class为准创建一个BeanDefinition，然后将该BeanDefinition作为当前
        // BeanDefinition的ref属性注入其中。
        // 这里parseProperties()方法会获取当前标签的所有<property/>子标签，
        // 然后将其属性注入到新生成的BeanDefinition中
        } else if (ServiceBean.class.equals(beanClass)) {
            // 获取class属性
            String className = element.getAttribute("class");
            if (className != null && className.length() > 0) {
                // 构建配置的class的BeanDefinition
                RootBeanDefinition classDefinition = new RootBeanDefinition();
                // 设置beanClass
                classDefinition.setBeanClass(ReflectUtils.forName(className));
                classDefinition.setLazyInit(false);
                /* 解析<property/>子标签 */
                parseProperties(element.getChildNodes(), classDefinition);
                // 添加ServiceBean ref属性的依赖
                beanDefinition.getPropertyValues().addPropertyValue("ref", new BeanDefinitionHolder(classDefinition, id + "Impl"));
            }
        // <dubbo:provider/>标签
        // 这里判断beanClass是否为ProviderConfig类型，如果是该类型，则将相关逻辑委托给parseNested()
        // 方法进行处理，该方法的主要有两个作用：
        // 1. 获取第一个标签名为service的子标签，判断其是否有default属性，如果有，则将该属性设置为当前
        //    BeanDefinition的default属性值，也就是将当前的provider作为默认的provider；
        // 2. 遍历得到所有的标签名为service的子标签，通过递归的方式在当前BeanDefinitionRegistry中注册
        //    注册ServiceBean，并且将其provider设置为当前父标签的provider。也就是说，通过这种方式，
        //    我们可以为特定的ServiceBean自定义设置其provider配置。
        } else if (ProviderConfig.class.equals(beanClass)) {
            /* 解析嵌套的元素 */
            parseNested(element, parserContext, ServiceBean.class, true, "service", "provider", id, beanDefinition);
        // <dubbo:consumer/>标签
        // 这里的逻辑与上面的provider的处理方式一致，即配置一个默认的consumer，然后将其子标签中定义的
        // reference设置默认的consumer为当前的consumer
        } else if (ConsumerConfig.class.equals(beanClass)) {
            /* 解析嵌套的元素 */
            parseNested(element, parserContext, ReferenceBean.class, false, "reference", "consumer", id, beanDefinition);
        }
        // 除去上面的特殊情况以外，下面的逻辑主要目的是获取当前beanClass中的各个属性名，然后获取当前标签
        // 中对应于该属性名的各个标签值，并将其转换到对应的属性中
        Set<String> props = new HashSet<String>();
        ManagedMap parameters = null;
        // 遍历beanClass的方法
        for (Method setter : beanClass.getMethods()) {
            String name = setter.getName();
            // 判断是否是public的有参数的setter方法
            if (name.length() > 3 && name.startsWith("set")
                    && Modifier.isPublic(setter.getModifiers())
                    && setter.getParameterTypes().length == 1) {
                Class<?> type = setter.getParameterTypes()[0];
                // 将setter驼峰命名去掉set后转成-连接的命名，如setApplicationContext --> application-context
                String property = StringUtils.camelToSplitName(name.substring(3, 4).toLowerCase() + name.substring(4), "-");
                props.add(property);
                Method getter = null;
                try {
                    // 获取对应属性的getter方法
                    getter = beanClass.getMethod("get" + name.substring(3), new Class<?>[0]);
                } catch (NoSuchMethodException e) {
                    try {
                        // boolean类型的属性的getter方法可能以is开头
                        getter = beanClass.getMethod("is" + name.substring(3), new Class<?>[0]);
                    } catch (NoSuchMethodException e2) {
                    }
                }
                // 如果没有getter方法或者getter方法不是public修饰符或者setter方法的参数类型与getter方法的返回值类型不同，直接忽略
                if (getter == null
                        || !Modifier.isPublic(getter.getModifiers())
                        || !type.equals(getter.getReturnType())) {
                    continue;
                }
                if ("parameters".equals(property)) {
                    /* parameters属性解析 */
                    // 获取当前标签的所有名称为parameter的子标签，将该标签中设置的属性值注入到当前
                    // BeanDefinition的parameters属性中
                    parameters = parseParameters(element.getChildNodes(), beanDefinition);
                } else if ("methods".equals(property)) {
                    /* methods属性解析 */
                    // 获取当前标签的所有名称为method的子标签，并将这每一个子标签都注册
                    // 为一个MethodConfig的对象，最终将这些对象注入到当前BeanDefinition
                    // 的methods属性中
                    parseMethods(id, element.getChildNodes(), beanDefinition, parserContext);
                } else if ("arguments".equals(property)) {
                    /* arguments属性解析 */
                    // 获取当前标签的所有名称为argument的子标签，并将这每一个子标签都注册为一个
                    // ArgumentConfig的对象，最终将这些对象注入到当前BeanDefinition
                    // 的arguments属性中
                    parseArguments(id, element.getChildNodes(), beanDefinition, parserContext);
                } else {
                    // 获取元素中的对应属性值
                    // 如果当前属性名不是上述的几种特例情况，则会在当前标签中获取与属性名同名的标签的值，
                    // 如果该值为空，则不进行处理
                    String value = element.getAttribute(property);
                    if (value != null) {
                        value = value.trim();
                        if (value.length() > 0) {
                            // registry属性设置为N/A
                            if ("registry".equals(property) && RegistryConfig.NO_AVAILABLE.equalsIgnoreCase(value)) {
                                // 如果当前属性名为registry，并且其值为N/A，则为期生成一个空的
                                // RegistryConfig对象注入到当前BeanDefinition中
                                RegistryConfig registryConfig = new RegistryConfig();
                                registryConfig.setAddress(RegistryConfig.NO_AVAILABLE);
                                beanDefinition.getPropertyValues().addPropertyValue(property, registryConfig);
                            } else if ("registry".equals(property) && value.indexOf(',') != -1) {
                                /* 多值registry设置 */
                                // 如果当前属性名为registry，并且其是包含多个注册中心的，
                                // 则为这每一个注册中心都生成一个RegistryConfig对象，
                                // 最终以list的形式保存到当前的BeanDefinition的registries属性中

                                parseMultiRef("registries", value, beanDefinition, parserContext);
                            } else if ("provider".equals(property) && value.indexOf(',') != -1) {
                                /* 多值provider设置 */
                                // 如果当前属性名为provider，并且其是包含有多个提供者的，
                                // 则为这每一个提供者都生成一个ProviderConfig对象，
                                // 最终以list的形式保存到当前BeanDefinition的providers属性中

                                parseMultiRef("providers", value, beanDefinition, parserContext);
                            } else if ("protocol".equals(property) && value.indexOf(',') != -1) {
                                /* 多值protocol设置 */
                                // 如果当前属性名为protocol，并且其是包含有多个提供者的，
                                // 则为这每一个protocol都生成一个ProtocolConfig对象，
                                // 最终以list的形式保存到当前BeanDefinition的protocols属性中

                                parseMultiRef("protocols", value, beanDefinition, parserContext);
                            } else {
                                Object reference;
                                // 判断方法的参数是否是基本类型，包括包装类型
                                if (isPrimitive(type)) {
                                    if ("async".equals(property) && "false".equals(value)
                                            || "timeout".equals(property) && "0".equals(value)
                                            || "delay".equals(property) && "0".equals(value)
                                            || "version".equals(property) && "0.0.0".equals(value)
                                            || "stat".equals(property) && "-1".equals(value)
                                            || "reliable".equals(property) && "false".equals(value)) {
                                        // backward compatibility for the default value in old version's xsd
                                        // 向后兼容旧版本的xsd中的默认值
                                        // 如果当前属性类型是基本数据类型，并且其值为默认值，
                                        // 则将当前属性设置为空

                                        value = null;
                                    }
                                    reference = value;
                                // protocol属性
                                } else if ("protocol".equals(property)
                                        && ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(value)
                                        && (!parserContext.getRegistry().containsBeanDefinition(value)
                                        || !ProtocolConfig.class.getName().equals(parserContext.getRegistry().getBeanDefinition(value).getBeanClassName()))) {
                                    // 如果当前属性名为protocol，并且当前SPI中包含有该protocol，
                                    // 则为其生成一个ProtocolConfig对象，存入到BeanDefinition中
                                    if ("dubbo:provider".equals(element.getTagName())) {
                                        logger.warn("Recommended replace <dubbo:provider protocol=\"" + value + "\" ... /> to <dubbo:protocol name=\"" + value + "\" ... />");
                                    }
                                    // backward compatibility
                                    ProtocolConfig protocol = new ProtocolConfig();
                                    protocol.setName(value);
                                    reference = protocol;
                                // onreturn属性
                                } else if ("onreturn".equals(property)) {
                                    // 如果当前的属性为onreturn，则获取当前属性值所指定的bean名称
                                    // 和方法名，将其设置到当前的BeanDefinition中

                                    int index = value.lastIndexOf(".");
                                    String returnRef = value.substring(0, index);
                                    String returnMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(returnRef);
                                    // 添加onreturnMethod属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onreturnMethod", returnMethod);
                                // onthrow属性
                                } else if ("onthrow".equals(property)) {
                                    // 如果当前属性为onthrow，则获取该属性所指定的bean名称和方法名，
                                    // 将其设置到当前的BeanDefinition中

                                    int index = value.lastIndexOf(".");
                                    String throwRef = value.substring(0, index);
                                    String throwMethod = value.substring(index + 1);
                                    reference = new RuntimeBeanReference(throwRef);
                                    // 添加onthrowMethod属性值
                                    beanDefinition.getPropertyValues().addPropertyValue("onthrowMethod", throwMethod);
                                } else {
                                    // 校验ref属性依赖的bean必须是单例的
                                    // 如果属性名为ref，并且当前BeanDefinitionRegistry中包含有
                                    // 该名称的bean，则将该bean注入到当前BeanDefinition中

                                    if ("ref".equals(property) && parserContext.getRegistry().containsBeanDefinition(value)) {
                                        BeanDefinition refBean = parserContext.getRegistry().getBeanDefinition(value);
                                        if (!refBean.isSingleton()) {
                                            throw new IllegalStateException("The exported service ref " + value + " must be singleton! Please set the " + value + " bean scope to singleton, eg: <bean id=\"" + value + "\" scope=\"singleton\" ...>");
                                        }
                                    }
                                    reference = new RuntimeBeanReference(value);
                                }
                                // 为相关属性添加依赖
                                beanDefinition.getPropertyValues().addPropertyValue(property, reference);
                            }
                        }
                    }
                }
            }
        }
        // 排除掉上面解析过的，剩余的属性添加到parameters属性中
        // 对于那些在标签中存在，但是在当前beanClass中不存在的属性，dubbo会将其以键值对的形式
        // 存入到当前BeanDefinition的parameters属性中

        NamedNodeMap attributes = element.getAttributes();
        int len = attributes.getLength();
        for (int i = 0; i < len; i++) {
            Node node = attributes.item(i);
            String name = node.getLocalName();
            if (!props.contains(name)) {
                if (parameters == null) {
                    parameters = new ManagedMap();
                }
                String value = node.getNodeValue();
                parameters.put(name, new TypedStringValue(value, String.class));
            }
        }
        if (parameters != null) {
            beanDefinition.getPropertyValues().addPropertyValue("parameters", parameters);
        }
        return beanDefinition;
    }

    private static boolean isPrimitive(Class<?> cls) {
        return cls.isPrimitive() || cls == Boolean.class || cls == Byte.class
                || cls == Character.class || cls == Short.class || cls == Integer.class
                || cls == Long.class || cls == Float.class || cls == Double.class
                || cls == String.class || cls == Date.class || cls == Class.class;
    }

    @SuppressWarnings("unchecked")
    private static void parseMultiRef(String property, String value, RootBeanDefinition beanDefinition,
                                      ParserContext parserContext) {
        // ,号分割value
        String[] values = value.split("\\s*[,]+\\s*");
        ManagedList list = null;
        for (int i = 0; i < values.length; i++) {
            String v = values[i];
            if (v != null && v.length() > 0) {
                if (list == null) {
                    list = new ManagedList();
                }
                list.add(new RuntimeBeanReference(v));
            }
        }
        // 添加对应属性的依赖
        beanDefinition.getPropertyValues().addPropertyValue(property, list);
    }

    private static void parseNested(Element element, ParserContext parserContext, Class<?> beanClass, boolean required, String tag, String property, String ref, BeanDefinition beanDefinition) {
        NodeList nodeList = element.getChildNodes();
        if (nodeList != null && nodeList.getLength() > 0) {
            boolean first = true;
            // 如果子节点不为null，遍历子节点
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // 判断节点名称是否与标签名称相同
                    if (tag.equals(node.getNodeName())
                            || tag.equals(node.getLocalName())) {
                        if (first) {
                            first = false;
                            String isDefault = element.getAttribute("default");
                            if (isDefault == null || isDefault.length() == 0) {
                                // 如果第一个子节点default属性为null，则设置为false
                                beanDefinition.getPropertyValues().addPropertyValue("default", "false");
                            }
                        }
                        // 递归解析嵌套的子节点
                        BeanDefinition subDefinition = parse((Element) node, parserContext, beanClass, required);
                        if (subDefinition != null && ref != null && ref.length() > 0) {
                            // 添加属性依赖
                            subDefinition.getPropertyValues().addPropertyValue(property, new RuntimeBeanReference(ref));
                        }
                    }
                }
            }
        }
    }

    private static void parseProperties(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            // 如果子节点不为null，遍历子节点
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // <property/>子标签
                    if ("property".equals(node.getNodeName())
                            || "property".equals(node.getLocalName())) {
                        String name = ((Element) node).getAttribute("name");// 提取name属性
                        if (name != null && name.length() > 0) {
                            // 提取value属性
                            String value = ((Element) node).getAttribute("value");
                            // 提取ref属性
                            String ref = ((Element) node).getAttribute("ref");
                            if (value != null && value.length() > 0) {
                                // value不为null，添加对应属性值
                                beanDefinition.getPropertyValues().addPropertyValue(name, value);
                            } else if (ref != null && ref.length() > 0) {
                                // ref不为null，添加对应属性依赖
                                beanDefinition.getPropertyValues().addPropertyValue(name, new RuntimeBeanReference(ref));
                            } else {
                                throw new UnsupportedOperationException("Unsupported <property name=\"" + name + "\"> sub tag, Only supported <property name=\"" + name + "\" ref=\"...\" /> or <property name=\"" + name + "\" value=\"...\" />");
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static ManagedMap parseParameters(NodeList nodeList, RootBeanDefinition beanDefinition) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedMap parameters = null;
            // 如果子节点不为null，遍历子节点
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    // 判断子节点名称是否是parameter
                    if ("parameter".equals(node.getNodeName())
                            || "parameter".equals(node.getLocalName())) {
                        if (parameters == null) {
                            parameters = new ManagedMap();
                        }
                        // 提取key属性值
                        String key = ((Element) node).getAttribute("key");
                        // 提取value属性值
                        String value = ((Element) node).getAttribute("value");
                        // 判断是否设置hide为true
                        boolean hide = "true".equals(((Element) node).getAttribute("hide"));
                        if (hide) {
                            // 如果设置了hide为true，则为key增加.前缀
                            key = Constants.HIDE_KEY_PREFIX + key;
                        }
                        parameters.put(key, new TypedStringValue(value, String.class));
                    }
                }
            }
            return parameters;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void parseMethods(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                     ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList methods = null;
            // 如果子节点不为null，遍历子节点
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    // 判断子节点名称是否是method
                    if ("method".equals(node.getNodeName()) || "method".equals(node.getLocalName())) {
                        // 提取name属性值
                        String methodName = element.getAttribute("name");
                        // name属性为null抛出异常
                        if (methodName == null || methodName.length() == 0) {
                            throw new IllegalStateException("<dubbo:method> name attribute == null");
                        }
                        if (methods == null) {
                            methods = new ManagedList();
                        }
                        // 递归解析method子节点
                        BeanDefinition methodBeanDefinition = parse(((Element) node),
                                parserContext, MethodConfig.class, false);
                        // 拼接name
                        String name = id + "." + methodName;
                        // 构造BeanDefinitionHolder
                        BeanDefinitionHolder methodBeanDefinitionHolder = new BeanDefinitionHolder(
                                methodBeanDefinition, name);
                        methods.add(methodBeanDefinitionHolder);
                    }
                }
            }
            if (methods != null) {
                // 如果不为null，添加对应属性的依赖
                beanDefinition.getPropertyValues().addPropertyValue("methods", methods);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void parseArguments(String id, NodeList nodeList, RootBeanDefinition beanDefinition,
                                       ParserContext parserContext) {
        if (nodeList != null && nodeList.getLength() > 0) {
            ManagedList arguments = null;
            // 如果子节点不为null，遍历子节点
            for (int i = 0; i < nodeList.getLength(); i++) {
                Node node = nodeList.item(i);
                if (node instanceof Element) {
                    Element element = (Element) node;
                    // 判断子节点名称是否是argument
                    if ("argument".equals(node.getNodeName()) || "argument".equals(node.getLocalName())) {
                        // 提取index属性值
                        String argumentIndex = element.getAttribute("index");
                        if (arguments == null) {
                            arguments = new ManagedList();
                        }
                        // 递归解析argument子节点
                        BeanDefinition argumentBeanDefinition = parse(((Element) node),
                                parserContext, ArgumentConfig.class, false);
                        // 拼接name
                        String name = id + "." + argumentIndex;
                        // 构造BeanDefinitionHolder
                        BeanDefinitionHolder argumentBeanDefinitionHolder = new BeanDefinitionHolder(
                                argumentBeanDefinition, name);
                        arguments.add(argumentBeanDefinitionHolder);
                    }
                }
            }
            if (arguments != null) {
                // 如果不为null，添加对应属性的依赖
                beanDefinition.getPropertyValues().addPropertyValue("arguments", arguments);
            }
        }
    }

    public BeanDefinition parse(Element element, ParserContext parserContext) {
        return parse(element, parserContext, beanClass, required);
    }

}