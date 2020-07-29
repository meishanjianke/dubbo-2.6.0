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
package com.alibaba.dubbo.rpc.cluster.loadbalance;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.RpcStatus;

import java.util.List;
import java.util.Random;

/**
 * LeastActiveLoadBalance
 * 最少活跃策略
 * LeastActiveLoadBalance 翻译过来是最小活跃数负载均衡。活跃调用数越小，表明该服务提供者效率越高，单位时间内可处理更多的请求。此时应优先将请求分配给该服务提供者。
 * 在具体实现中，每个服务提供者对应一个活跃数 active。初始情况下，所有服务提供者活跃数均为0。每收到一个请求，活跃数加1，完成请求后则将活跃数减1。在服务运行一段时间后，
 * 性能好的服务提供者处理请求的速度更快，因此活跃数下降的也越快，此时这样的服务提供者能够优先获取到新的服务请求、这就是最小活跃数负载均衡算法的基本思想。除了最小活跃数，
 * LeastActiveLoadBalance 在实现上还引入了权重值。所以准确的来说，LeastActiveLoadBalance 是基于加权最小活跃数算法实现的。举个例子说明一下，在一个服务提供者集群中，
 * 有两个性能优异的服务提供者。某一时刻它们的活跃数相同，此时 Dubbo 会根据它们的权重去分配请求，权重越大，获取到新请求的概率就越大。如果两个服务提供者权重相同，
 * 此时随机选择一个即可。关于 LeastActiveLoadBalance 的背景知识就先介绍到这里，下面开始分析源码。
 */
public class LeastActiveLoadBalance extends AbstractLoadBalance {

    public static final String NAME = "leastactive";

    private final Random random = new Random();

    /**
     * 上面代码的逻辑比较多，我们在代码中写了大量的注释，有帮助大家理解代码逻辑。下面简单总结一下以上代码所做的事情，如下：
     *
     * 遍历 invokers 列表，寻找活跃数最小的 Invoker
     * 如果有多个 Invoker 具有相同的最小活跃数，此时记录下这些 Invoker 在 invokers 集合中的下标，并累加它们的权重，比较它们的权重值是否相等
     * 如果只有一个 Invoker 具有最小的活跃数，此时直接返回该 Invoker 即可
     * 如果有多个 Invoker 具有最小活跃数，且它们的权重不相等，此时处理方式和 RandomLoadBalance 一致
     * 如果有多个 Invoker 具有最小活跃数，但它们的权重相等，此时随机返回一个即可
     * 以上就是 LeastActiveLoadBalance 大致的实现逻辑，大家在阅读的源码的过程中要注意区分活跃数与权重这两个概念，不要混为一谈。
     * @param invokers
     * @param url
     * @param invocation
     * @param <T>
     * @return
     */
    @Override
    protected <T> Invoker<T> doSelect(List<Invoker<T>> invokers, URL url, Invocation invocation) {
        int length = invokers.size(); // Number of invokers
        // 所有invoker的最小活跃值
        int leastActive = -1; // The least active value of all invokers
        // 具有相同最小活跃值的invoker数量（leastActive）
        int leastCount = 0; // The number of invokers having the same least active value (leastActive)
        // 具有相同最小活跃值的invoker索引（leastActive）
        int[] leastIndexs = new int[length]; // The index of invokers having the same least active value (leastActive)
        // 权重和，总权重
        int totalWeight = 0; // The sum of weights
        // 初始化权重，用来做比较
        int firstWeight = 0; // Initial value, used for comparision
        // 每一个invoker是否有相同的权重值
        boolean sameWeight = true; // Every invoker has the same weight value?
        for (int i = 0; i < length; i++) {
            Invoker<T> invoker = invokers.get(i);
            // 活跃数量
            int active = RpcStatus.getStatus(invoker.getUrl(), invocation.getMethodName()).getActive(); // Active number
            // 权重
            int weight = invoker.getUrl().getMethodParameter(invocation.getMethodName(), Constants.WEIGHT_KEY, Constants.DEFAULT_WEIGHT); // Weight
            // 当发现具有较小活跃值的invoker时重新开始
            if (leastActive == -1 || active < leastActive) { // Restart, when find a invoker having smaller least active value.
                // 记录当前最小活跃值
                leastActive = active; // Record the current least active value
                // 重置具有相同最小活跃值的invoker数量，根据当前最小活跃值的invoker数量再次计数
                leastCount = 1; // Reset leastCount, count again based on current leastCount
                // 重置
                leastIndexs[0] = i; // Reset
                // 重置
                totalWeight = weight; // Reset
                // 记录第一个invoker的权重
                firstWeight = weight; // Record the weight the first invoker
                // 重置
                sameWeight = true; // Reset, every invoker has the same weight value?
            // 如果当前调invoker的活动值等于leaseActive，则累积
            } else if (active == leastActive) { // If current invoker's active value equals with leaseActive, then accumulating.
                // 记录这个invoker的index
                leastIndexs[leastCount++] = i; // Record index number of this invoker
                // 累加这个invoker的权重到总权重
                totalWeight += weight; // Add this invoker's weight to totalWeight.
                // If every invoker has the same weight?
                // 与初始化权重进行比较，判断是否每一个invoker都具有相同的权重值
                if (sameWeight && i > 0
                        && weight != firstWeight) {
                    sameWeight = false;
                }
            }
        }
        // assert(leastCount > 0)
        if (leastCount == 1) {
            // 如果我们只有一个具有最小活跃值的invoker，则直接返回此invoker
            // If we got exactly one invoker having the least active value, return this invoker directly.
            return invokers.get(leastIndexs[0]);
        }
        if (!sameWeight && totalWeight > 0) {
            // If (not every invoker has the same weight & at least one invoker's weight>0), select randomly based on totalWeight.
            // 如果（并非每个invoker具有相同的权重并且至少一个invoker的权重 > 0），则根据totalWeight随机选择
            int offsetWeight = random.nextInt(totalWeight);
            // Return a invoker based on the random value.
            // 根据随机值获取一个invoker
            for (int i = 0; i < leastCount; i++) {
                int leastIndex = leastIndexs[i];
                offsetWeight -= getWeight(invokers.get(leastIndex), invocation);
                if (offsetWeight <= 0)
                    return invokers.get(leastIndex);
            }
        }
        // If all invokers have the same weight value or totalWeight=0, return evenly.
        // 如果所有调用者具有相同的权重值或totalWeight = 0，则从具有最小活跃值的集合中随机返回一个
        return invokers.get(leastIndexs[random.nextInt(leastCount)]);
    }
}