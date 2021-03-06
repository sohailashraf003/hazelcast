/*
 * Copyright (c) 2008-2017, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.spi.impl.operationservice.impl;

import com.hazelcast.config.Config;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.MemberLeftException;
import com.hazelcast.instance.Node;
import com.hazelcast.instance.TestUtil;
import com.hazelcast.internal.cluster.impl.ClusterServiceImpl;
import com.hazelcast.spi.AbstractWaitNotifyKey;
import com.hazelcast.spi.BlockingOperation;
import com.hazelcast.spi.ExceptionAction;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.OperationService;
import com.hazelcast.spi.WaitNotifyKey;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.spi.impl.operationparker.impl.OperationParkerImpl;
import com.hazelcast.spi.properties.GroupProperty;
import com.hazelcast.test.AssertTask;
import com.hazelcast.test.HazelcastParallelClassRunner;
import com.hazelcast.test.HazelcastTestSupport;
import com.hazelcast.test.TestHazelcastInstanceFactory;
import com.hazelcast.test.annotation.ParallelTest;
import com.hazelcast.test.annotation.QuickTest;
import com.hazelcast.util.EmptyStatement;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(HazelcastParallelClassRunner.class)
@Category({QuickTest.class, ParallelTest.class})
public class Invocation_NetworkSplitTest extends HazelcastTestSupport {

    @Test
    public void testWaitingInvocations_whenNodeSplitFromCluster() throws Exception {
        SplitAction action = new FullSplitAction();
        testWaitingInvocations_whenNodeSplitFromCluster(action);
    }

    @Test
    public void testWaitingInvocations_whenNodePartiallySplitFromCluster() throws Exception {
        SplitAction action = new PartialSplitAction();
        testWaitingInvocations_whenNodeSplitFromCluster(action);
    }

    private void testWaitingInvocations_whenNodeSplitFromCluster(SplitAction splitAction) throws Exception {
        Config config = createConfig();
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(3);
        HazelcastInstance hz1 = factory.newHazelcastInstance(config);
        HazelcastInstance hz2 = factory.newHazelcastInstance(config);
        HazelcastInstance hz3 = factory.newHazelcastInstance(config);

        Node node1 = TestUtil.getNode(hz1);
        Node node2 = TestUtil.getNode(hz2);
        Node node3 = TestUtil.getNode(hz3);

        warmUpPartitions(hz1, hz2, hz3);
        int partitionId = getPartitionId(hz2);

        NodeEngineImpl nodeEngine3 = node3.getNodeEngine();
        OperationService operationService3 = nodeEngine3.getOperationService();
        Operation op = new AlwaysBlockingOperation();
        Future<Object> future = operationService3.invokeOnPartition("", op, partitionId);

        // just wait a little to make sure
        // operation is landed on wait-queue
        sleepSeconds(1);

        // execute the given split action
        splitAction.run(node1, node2, node3);

        // Let node3 detect the split and merge it back to other two.
        ClusterServiceImpl clusterService3 = node3.getClusterService();
        clusterService3.merge(node1.address);

        assertClusterSizeEventually(3, hz1);
        assertClusterSizeEventually(3, hz2);
        assertClusterSizeEventually(3, hz3);

        try {
            future.get(1, TimeUnit.MINUTES);
            fail("Future.get() should fail with a MemberLeftException!");
        } catch (MemberLeftException e) {
            // expected
            EmptyStatement.ignore(e);
        } catch (Exception e) {
            fail(e.getClass().getName() + ": " + e.getMessage());
        }
    }

    @Test
    public void testWaitNotifyService_whenNodeSplitFromCluster() throws Exception {
        SplitAction action = new FullSplitAction();
        testWaitNotifyService_whenNodeSplitFromCluster(action);
    }

    @Test
    public void testWaitNotifyService_whenNodePartiallySplitFromCluster() throws Exception {
        SplitAction action = new PartialSplitAction();
        testWaitNotifyService_whenNodeSplitFromCluster(action);
    }

    private void testWaitNotifyService_whenNodeSplitFromCluster(SplitAction action) throws Exception {
        Config config = createConfig();
        TestHazelcastInstanceFactory factory = createHazelcastInstanceFactory(5);
        HazelcastInstance hz1 = factory.newHazelcastInstance(config);
        HazelcastInstance hz2 = factory.newHazelcastInstance(config);
        HazelcastInstance hz3 = factory.newHazelcastInstance(config);

        final Node node1 = TestUtil.getNode(hz1);
        Node node2 = TestUtil.getNode(hz2);
        Node node3 = TestUtil.getNode(hz3);

        warmUpPartitions(hz1, hz2, hz3);
        int partitionId = getPartitionId(hz3);

        NodeEngineImpl nodeEngine1 = node1.getNodeEngine();
        OperationService operationService1 = nodeEngine1.getOperationService();
        operationService1.invokeOnPartition("", new AlwaysBlockingOperation(), partitionId);

        final OperationParkerImpl waitNotifyService3 = (OperationParkerImpl) node3.getNodeEngine().getOperationParker();
        assertEqualsEventually(new Callable<Integer>() {
            @Override
            public Integer call() throws Exception {
                return waitNotifyService3.getTotalParkedOperationCount();
            }
        }, 1);

        action.run(node1, node2, node3);

        // create a new node to prevent same partition assignments
        // after node3 rejoins
        factory.newHazelcastInstance(config);

        assertTrueEventually(new AssertTask() {
            @Override
            public void run() throws Exception {
                Assert.assertEquals(0, node1.partitionService.getMigrationQueueSize());
            }
        });

        // Let node3 detect the split and merge it back to other two.
        ClusterServiceImpl clusterService3 = node3.getClusterService();
        clusterService3.merge(node1.address);

        assertEquals(4, node1.getClusterService().getSize());
        assertEquals(4, node2.getClusterService().getSize());
        assertEquals(4, node3.getClusterService().getSize());

        assertEquals(0, waitNotifyService3.getTotalParkedOperationCount());
    }

    private Config createConfig() {
        Config config = new Config();
        config.getGroupConfig().setName(generateRandomString(10));
        config.setProperty(GroupProperty.MASTER_CONFIRMATION_INTERVAL_SECONDS.getName(), "1");
        config.setProperty(GroupProperty.MEMBER_LIST_PUBLISH_INTERVAL_SECONDS.getName(), "10");
        return config;
    }

    private static class AlwaysBlockingOperation extends Operation implements BlockingOperation {

        @Override
        public void run() throws Exception {
        }

        @Override
        public WaitNotifyKey getWaitKey() {
            return new AbstractWaitNotifyKey(getServiceName(), "test") {
            };
        }

        @Override
        public boolean shouldWait() {
            return true;
        }

        @Override
        public void onWaitExpire() {
            sendResponse(new TimeoutException());
        }

        @Override
        public String getServiceName() {
            return "AlwaysBlockingOperationService";
        }

        @Override
        public ExceptionAction onInvocationException(Throwable throwable) {
            return ExceptionAction.THROW_EXCEPTION;
        }
    }

    private interface SplitAction {
        void run(Node node1, Node node2, Node node3);
    }

    private static class FullSplitAction implements SplitAction {

        @Override
        public void run(final Node node1, final Node node2, final Node node3) {
            // Artificially create a network-split
            node1.clusterService.suspectMember(node3.getLocalMember(), null, true);

            node3.clusterService.suspectMember(node1.getLocalMember(), null, true);
            node3.clusterService.suspectMember(node2.getLocalMember(), null, true);

            assertTrueEventually(new AssertTask() {
                @Override
                public void run() throws Exception {
                    assertEquals(2, node1.getClusterService().getSize());
                    assertEquals(2, node2.getClusterService().getSize());
                    assertEquals(1, node3.getClusterService().getSize());
                }
            }, 10);
        }
    }

    private static class PartialSplitAction implements SplitAction {

        @Override
        public void run(final Node node1, final Node node2, final Node node3) {
            // Artificially create a partial network-split;
            // node1 and node2 will be split from node3
            // but node 3 will not be able to detect that.
            node1.clusterService.suspectMember(node3.getLocalMember(), null, true);

            assertTrueEventually(new AssertTask() {
                @Override
                public void run() throws Exception {
                    assertEquals(2, node1.getClusterService().getSize());
                    assertEquals(2, node2.getClusterService().getSize());
                    assertEquals(1, node3.getClusterService().getSize());
                }
            }, 10);
        }
    }
}
