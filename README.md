## 框架设计
http://dubbo.apache.org/zh-cn/docs/dev/design.html

### Dubbo中常用有7个标签。
分为三个类别：公用标签，服务提供者标签，服务消费者标签，

#### 1、公用标签

<dubbo:application/>和  <dubbo:registry/>

A、配置应用信息

    <dubbo:application name="服务的名称"/>

B、配置注册中心

    <dubbo:registry address="ip:port" protocol="协议"/>

#### 2、服务提供者标签

A、配置服务提供者

    <dubbo:provider/>设置<dubbo:service>和<dubbo:protocol>标签的默认值  
    <dubbo:provider protocol="协议" host="主机 ip" />
B、配置服务提供者的访问协议

    <dubbo:protocol name="dubbo" port="20880"/>
C、配置服务提供者暴露自己的服务

    <dubbo:service interface="服务接口名"  ref="服务实现对象 bean">

#### 3、服务消费者标签

A、配置服务消费者的默认值

    <dubbo:consumer/>  配置服务消费者的默认值，即<dubbo:reference>标签的默认值
    <dubbo:consumertimeout=”1000” retries=”2”  />  默认远程连接超时 1000 毫秒，重新连接次数 2
B、配置服务消费者引用服务

    <dubbo:referenceid=”服务引用 bean 的 id” interface=”服务接口名”/>

### Dubbo源码阅读顺序

原文链接：https://blog.csdn.net/heroqiang/article/details/85340958

### java spi 和dubbo spi 的区别
#### 1、JDK SPI
JDK 标准的 SPI 会一次性加载所有的扩展实现，如果有的扩展吃实话很耗时，但
也没用上，很浪费资源。
所以只希望加载某个的实现，就不现实了

#### DUBBO SPI
1，对 Dubbo 进行扩展，不需要改动 Dubbo 的源码
2，延迟加载，可以一次只加载自己想要加载的扩展实现。
3，增加了对扩展点 IOC 和 AOP 的支持，一个扩展点可以直接 setter 注入其它扩展点。
3，Dubbo 的扩展机制能很好的支持第三方 IoC 容器，默认支持 Spring Bean。

### 自适应扩展机制
首先 Dubbo 会为拓展接口生成具有代理功能的代码。然后通过 javassist 或 jdk 编译这段代码，得到 Class 类。
最后再通过反射创建代理类，在代理类中，就可以通过URL对象的参数来确定到底调用哪个实现类。

### dubbo ioc
Dubbo IOC 是通过 setter 方法注入依赖。Dubbo 首先会通过反射获取到实例的所有方法，然后再遍历方法列表，检测方法名是否具有 setter 方法特征。
若有，则通过 ObjectFactory 获取依赖对象，最后通过反射调用 setter 方法将依赖设置到目标对象中。

### dubbo 服务导出
Dubbo 服务导出过程始于 Spring 容器发布刷新事件，Dubbo 在接收到事件后，会立即执行服务导出逻辑。
整个逻辑大致可分为三个部分，
第一部分是前置工作，主要用于检查参数，组装 URL。
第二部分是导出服务，包含导出服务到本地 (JVM)，和导出服务到远程两个过程。
ZookeeperClient第三部分是向注册中心注册服务，用于服务发现。
#### 节点创建
以 Zookeeper 为例，所谓的服务注册，本质上是将服务配置数据写入到 Zookeeper 的某个路径的节点下。为了让大家有一个直观的了解，下面我们将 Dubbo 的 demo 跑起来，然后通过 Zookeeper 可视化客户端 ZooInspector 查看节点数据。如下：
![](./img/节点创建.png)
从上图中可以看到 com.alibaba.dubbo.demo.DemoService 这个服务对应的配置信息（存储在 URL 中）最终被注册到了 /dubbo/com.alibaba.dubbo.demo.DemoService/providers/ 节点下。

### dubbo 服务引用
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

Invoker 是 Dubbo 的核心模型，代表一个可执行体。在服务提供方，Invoker 用于调用服务提供类。在服务消费方，Invoker 用于执行远程调用。Invoker 是由 Protocol 实现类构建而来。

### 服务字典
服务目录是什么。服务目录中存储了一些和服务提供者有关的信息，通过服务目录，服务消费者可获取到服务提供者的信息，比如 ip、端口、服务协议等。通过这些信息，
服务消费者就可通过 Netty 等客户端进行远程调用。在一个服务集群中，服务提供者数量并不是一成不变的，如果集群中新增了一台机器，相应地在服务目录中就要新增一条服务提供者记录。
或者，如果服务提供者的配置修改了，服务目录中的记录也要做相应的更新。如果这样说，服务目录和注册中心的功能不就雷同了吗？确实如此，这里这么说是为了方便大家理解。
实际上服务目录在获取注册中心的服务配置信息后，会为每条配置信息生成一个 Invoker 对象，并把这个 Invoker 对象存储起来，这个 Invoker 才是服务目录最终持有的对象。
Invoker 有什么用呢？看名字就知道了，这是一个具有远程调用功能的对象。讲到这大家应该知道了什么是服务目录了，它可以看做是 Invoker 集合，
且这个集合中的元素会随注册中心的变化而进行动态调整。

### 服务路由
服务路由是什么。服务路由包含一条路由规则，路由规则决定了服务消费者的调用目标，即规定了服务消费者可调用哪些服务提供者。Dubbo 目前提供了三种服务路由实现，
分别为条件路由 ConditionRouter、脚本路由 ScriptRouter 和标签路由 TagRouter。其中条件路由是我们最常使用的，标签路由是一个新的实现，暂时还未发布，
该实现预计会在 2.7.x 版本中发布。

### 集群
在对集群相关代码进行分析之前，这里有必要先来介绍一下集群容错的所有组件。包含 Cluster、Cluster Invoker、Directory、Router 和 LoadBalance 等。
![](./img/集群.png)

集群工作过程可分为两个阶段，第一个阶段是在服务消费者初始化期间，集群 Cluster 实现类为服务消费者创建 Cluster Invoker 实例，即上图中的 merge 操作。
第二个阶段是在服务消费者进行远程调用时。以 FailoverClusterInvoker 为例，该类型 Cluster Invoker 首先会调用 Directory 的 list 方法列举 Invoker 
列表（可将 Invoker 简单理解为服务提供者）。Directory 的用途是保存 Invoker，可简单类比为 List<Invoker>。其实现类 RegistryDirectory 是一个动态服务目录，
可感知注册中心配置的变化，它所持有的 Invoker 列表会随着注册中心内容的变化而变化。每次变化后，RegistryDirectory 会动态增删 Invoker，并调用 Router 的 route 
方法进行路由，过滤掉不符合路由规则的 Invoker。当 FailoverClusterInvoker 拿到 Directory 返回的 Invoker 列表后，它会通过 LoadBalance 从 Invoker 
列表中选择一个 Invoker。最后 FailoverClusterInvoker 会将参数传给 LoadBalance 选择出的 Invoker 实例的 invoke 方法，进行真正的远程调用。

以上就是集群工作的整个流程，这里并没介绍集群是如何容错的。Dubbo 主要提供了这样几种容错方式：
- Failover Cluster - 失败自动切换
- Failfast Cluster - 快速失败
- Failsafe Cluster - 失败安全
- Failback Cluster - 失败自动恢复
- Forking Cluster - 并行调用多个服务提供者
