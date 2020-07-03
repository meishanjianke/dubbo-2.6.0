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

https://blog.csdn.net/qq_33404395/article/details/86498060?utm_medium=distribute.pc_relevant.none-task-blog-BlogCommendFromMachineLearnPai2-1.nonecase&depth_1-utm_source=distribute.pc_relevant.none-task-blog-BlogCommendFromMachineLearnPai2-1.nonecase