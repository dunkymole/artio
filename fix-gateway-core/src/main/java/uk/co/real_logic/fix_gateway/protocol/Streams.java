/*
 * Copyright 2015-2016 Real Logic Ltd.
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
package uk.co.real_logic.fix_gateway.protocol;

import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.NanoClock;
import org.agrona.concurrent.status.AtomicCounter;
import uk.co.real_logic.fix_gateway.ReliefValve;
import uk.co.real_logic.fix_gateway.replication.ClusterableNode;
import uk.co.real_logic.fix_gateway.replication.ClusterablePublication;
import uk.co.real_logic.fix_gateway.replication.ClusterableSubscription;

import static uk.co.real_logic.fix_gateway.ReliefValve.NO_RELIEF_VALVE;

public class Streams
{
    private final int streamId;
    private final NanoClock nanoClock;
    private final ClusterableNode node;
    private final AtomicCounter failedPublications;
    private final int maxClaimAttempts;

    public Streams(
        final ClusterableNode node,
        final AtomicCounter failedPublications,
        final int streamId,
        final NanoClock nanoClock,
        final int maxClaimAttempts)
    {
        this.node = node;
        this.failedPublications = failedPublications;
        this.streamId = streamId;
        this.nanoClock = nanoClock;
        this.maxClaimAttempts = maxClaimAttempts;
    }

    public GatewayPublication gatewayPublication(final IdleStrategy idleStrategy)
    {
        return gatewayPublication(idleStrategy, NO_RELIEF_VALVE);
    }

    public GatewayPublication gatewayPublication(final IdleStrategy idleStrategy, final ReliefValve reliefValve)
    {
        return new GatewayPublication(
            dataPublication(),
            failedPublications,
            idleStrategy,
            nanoClock,
            maxClaimAttempts,
            reliefValve);
    }

    public ClusterablePublication dataPublication()
    {
        return node.publication(streamId);
    }

    public ClusterableSubscription subscription()
    {
        return node.subscription(streamId);
    }
}
