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

package com.hazelcast.client.impl.protocol.task.semaphore;

import com.hazelcast.client.impl.protocol.ClientMessage;
import com.hazelcast.client.impl.protocol.codec.SemaphoreAcquireCodec;
import com.hazelcast.client.impl.protocol.task.AbstractPartitionMessageTask;
import com.hazelcast.concurrent.semaphore.SemaphoreService;
import com.hazelcast.concurrent.semaphore.operations.AcquireOperation;
import com.hazelcast.instance.Node;
import com.hazelcast.nio.Connection;
import com.hazelcast.security.permission.ActionConstants;
import com.hazelcast.security.permission.SemaphorePermission;
import com.hazelcast.spi.Operation;

import java.security.Permission;


public class SemaphoreAcquireMessageTask
        extends AbstractPartitionMessageTask<SemaphoreAcquireCodec.RequestParameters> {

    public SemaphoreAcquireMessageTask(ClientMessage clientMessage, Node node, Connection connection) {
        super(clientMessage, node, connection);
    }

    @Override
    protected Operation prepareOperation() {
        return new AcquireOperation(parameters.name, parameters.permits, -1);
    }

    @Override
    protected SemaphoreAcquireCodec.RequestParameters decodeClientMessage(ClientMessage clientMessage) {
        return SemaphoreAcquireCodec.decodeRequest(clientMessage);
    }

    @Override
    protected ClientMessage encodeResponse(Object response) {
        return SemaphoreAcquireCodec.encodeResponse();
    }

    @Override
    public String getServiceName() {
        return SemaphoreService.SERVICE_NAME;
    }

    @Override
    public Permission getRequiredPermission() {
        return new SemaphorePermission(parameters.name, ActionConstants.ACTION_ACQUIRE);
    }

    @Override
    public String getDistributedObjectName() {
        return parameters.name;
    }

    @Override
    public String getMethodName() {
        return "acquire";
    }

    @Override
    public Object[] getParameters() {
        return new Object[]{parameters.permits};
    }

}
