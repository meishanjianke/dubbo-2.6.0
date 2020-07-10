框架设计
http://dubbo.apache.org/zh-cn/docs/dev/design.html

Dubbo中常用有7个标签。
分为三个类别：公用标签，服务提供者标签，服务消费者标签
公用标签
<dubbo:application/>和  <dubbo:registry/>
A、配置应用信息
<dubbo:application name="服务的名称"/>
B、配置注册中心
<dubbo:registry address="ip:port" protocol="协议"/>

服务提供者标签
A、配置服务提供者
<dubbo:provider/>设置<dubbo:service>和<dubbo:protocol>标签的默认值  
<dubbo:provider protocol="协议" host="主机 ip" />
B、配置服务提供者的访问协议
<dubbo:protocol name="dubbo" port="20880"/>
C、配置服务提供者暴露自己的服务
<dubbo:service interface="服务接口名"  ref="服务实现对象 bean">

服务消费者标签
A、配置服务消费者的默认值
<dubbo:consumer/>  配置服务消费者的默认值，即<dubbo:reference>标签的默认值
<dubbo:consumertimeout=”1000” retries=”2”  />  默认远程连接超时 1000 毫秒，重新连接次数 2
B、配置服务消费者引用服务
<dubbo:referenceid=”服务引用 bean 的 id” interface=”服务接口名”/>

Dubbo源码阅读顺序
原文链接：https://blog.csdn.net/heroqiang/article/details/85340958

------------------java spi 和dubbo spi 的区别--------------------
JDK SPI
JDK 标准的 SPI 会一次性加载所有的扩展实现，如果有的扩展吃实话很耗时，但
也没用上，很浪费资源。
所以只希望加载某个的实现，就不现实了

DUBBO SPI
1，对 Dubbo 进行扩展，不需要改动 Dubbo 的源码
2，延迟加载，可以一次只加载自己想要加载的扩展实现。
3，增加了对扩展点 IOC 和 AOP 的支持，一个扩展点可以直接 setter 注入其它扩展点。
3，Dubbo 的扩展机制能很好的支持第三方 IoC 容器，默认支持 Spring Bean。

--------------------------------自适应扩展机制-------------
首先 Dubbo 会为拓展接口生成具有代理功能的代码。然后通过 javassist 或 jdk 编译这段代码，得到 Class 类。
最后再通过反射创建代理类，在代理类中，就可以通过URL对象的参数来确定到底调用哪个实现类。

-------------------------------dubbo ioc-------------
Dubbo IOC 是通过 setter 方法注入依赖。Dubbo 首先会通过反射获取到实例的所有方法，然后再遍历方法列表，检测方法名是否具有 setter 方法特征。
若有，则通过 ObjectFactory 获取依赖对象，最后通过反射调用 setter 方法将依赖设置到目标对象中。

-----------------------------dubbo 服务暴露------------
Dubbo 服务导出过程始于 Spring 容器发布刷新事件，Dubbo 在接收到事件后，会立即执行服务导出逻辑。
整个逻辑大致可分为三个部分，
第一部分是前置工作，主要用于检查参数，组装 URL。
第二部分是导出服务，包含导出服务到本地 (JVM)，和导出服务到远程两个过程。
ZookeeperClient第三部分是向注册中心注册服务，用于服务发现。

-----------------------------dubbo 服务引用------------
Dubbo 服务引用的时机有两个，第一个是在 Spring 容器调用 ReferenceBean 的 afterPropertiesSet 方法时引用服务，
第二个是在 ReferenceBean 对应的服务被注入到其他类中时引用。这两个引用服务的时机区别在于，第一个是饿汉式的，第二个是懒汉式的。
默认情况下，Dubbo 使用懒汉式引用服务。如果需要使用饿汉式，可通过配置 <dubbo:reference> 的 init 属性开启。
下面我们按照 Dubbo 默认配置进行分析，整个分析过程从 ReferenceBean 的 getObject 方法开始。当我们的服务被注入到其他类中时，
Spring 会第一时间调用 getObject 方法，并由该方法执行服务引用逻辑。按照惯例，在进行具体工作之前，需先进行配置检查与收集工作。
接着根据收集到的信息决定服务用的方式，有三种，第一种是引用本地 (JVM) 服务，第二是通过直连方式引用远程服务，第三是通过注册中心引用远程服务。
不管是哪种引用方式，最后都会得到一个 Invoker 实例。如果有多个注册中心，多个服务提供者，这个时候会得到一组 Invoker 实例，
此时需要通过集群管理类 Cluster 将多个 Invoker 合并成一个实例。合并后的 Invoker 实例已经具备调用本地或远程服务的能力了，
但并不能将此实例暴露给用户使用，这会对用户业务代码造成侵入。此时框架还需要通过代理工厂类 (ProxyFactory) 为服务接口生成代理类，
并让代理类去调用 Invoker 逻辑。避免了 Dubbo 框架代码对业务代码的侵入，同时也让框架更容易使用。
