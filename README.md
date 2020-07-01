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