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
package uk.co.real_logic.fix_gateway.engine;

import io.aeron.Publication;
import io.aeron.Subscription;
import org.agrona.LangUtil;
import org.agrona.concurrent.*;
import uk.co.real_logic.fix_gateway.FixCounters;
import uk.co.real_logic.fix_gateway.GatewayProcess;
import uk.co.real_logic.fix_gateway.engine.framer.*;
import uk.co.real_logic.fix_gateway.engine.logger.Context;
import uk.co.real_logic.fix_gateway.engine.logger.SequenceNumberIndexReader;
import uk.co.real_logic.fix_gateway.protocol.Streams;
import uk.co.real_logic.fix_gateway.replication.ClusterableSubscription;
import uk.co.real_logic.fix_gateway.session.SessionIdStrategy;
import uk.co.real_logic.fix_gateway.timing.EngineTimers;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.agrona.concurrent.AgentRunner.startOnThread;

/**
 * A FIX Engine is a process in the gateway that accepts or initiates FIX connections and
 * hands them off to different FixLibrary instances. The engine can replicate and/or durably
 * store streams of FIX messages for replay, archival, administrative or analytics purposes.
 * <p>
 * Each engine can have one or more associated libraries that manage sessions and perform business
 * logic. These may run in the same JVM process or a different JVM process.
 *
 * @see uk.co.real_logic.fix_gateway.library.FixLibrary
 */
public final class FixEngine extends GatewayProcess
{
    public static final int GATEWAY_LIBRARY_ID = 0;

    private QueuedPipe<AdminCommand> adminCommands = new ManyToOneConcurrentArrayQueue<>(16);

    private final EngineTimers timers = new EngineTimers();
    private final EngineConfiguration configuration;

    private AgentRunner framerRunner;
    private Context context;

    /**
     * Launch the engine. This method starts up the engine threads and then returns.
     *
     * @param configuration the configuration to use for this engine.
     * @return the new FIX engine instance.
     */
    public static FixEngine launch(final EngineConfiguration configuration)
    {
        configuration.conclude();

        return new FixEngine(configuration).launch();
    }

    /**
     * Query the engine for the list of libraries currently active.
     *
     * @param idleStrategy the strategy to idle with whilst waiting for a response.
     * @return a list of currently active libraries.
     */
    public List<LibraryInfo> libraries(final IdleStrategy idleStrategy)
    {
        final QueryLibrariesCommand command = new QueryLibrariesCommand();
        sendAdminCommand(idleStrategy, command);

        return command.awaitResponse(idleStrategy);
    }

    /**
     * Query the engine for the lise of sessions currently managed.
     *
     * @param idleStrategy the strategy to idle with whilst waiting for a response.
     * @return the lise of sessions currently managed.
     */
    public List<SessionInfo> gatewaySessions(final IdleStrategy idleStrategy)
    {
        final GatewaySessionsCommand command = new GatewaySessionsCommand();
        sendAdminCommand(idleStrategy, command);

        return command.awaitResponse(idleStrategy);
    }

    /**
     * Resets the set of session ids.
     *
     * @param backupLocation the location to backup the current session ids file to.
     *                       Can be null to indicate that nobackup is required.
     * @throws IOException thrown in the case that there was an error in backing up,
     *                     or that there were currently connected sessions.
     *                     NB: genuinely thrown even though not checked.
     */
    public void resetSessionIds(final File backupLocation, final IdleStrategy idleStrategy)
    {
        if (backupLocation != null && !backupLocation.exists())
        {
            try
            {
                backupLocation.createNewFile();
            }
            catch (IOException e)
            {
                LangUtil.rethrowUnchecked(e);
            }
        }

        final ResetSessionIdsCommand command = new ResetSessionIdsCommand(backupLocation);
        sendAdminCommand(idleStrategy, command);
        command.awaitResponse(idleStrategy);
    }

    private void sendAdminCommand(final IdleStrategy idleStrategy, final AdminCommand query)
    {
        while (!adminCommands.offer(query))
        {
            idleStrategy.idle();
        }
        idleStrategy.reset();
    }

    private FixEngine(final EngineConfiguration configuration)
    {
        init(configuration);
        this.configuration = configuration;

        context = Context.of(
            configuration,
            errorHandler,
            replayPublication(),
            fixCounters,
            aeron);
        initFramer(configuration, fixCounters);
        initMonitoringAgent(timers.all(), configuration);
    }

    private Publication replayPublication()
    {
        return aeron.addPublication(configuration.libraryAeronChannel(), OUTBOUND_REPLAY_STREAM);
    }

    private void initFramer(final EngineConfiguration configuration, final FixCounters fixCounters)
    {
        final SessionIdStrategy sessionIdStrategy = configuration.sessionIdStrategy();
        final SessionIds sessionIds = new SessionIds(configuration.sessionIdBuffer(), sessionIdStrategy, errorHandler);
        final IdleStrategy idleStrategy = configuration.framerIdleStrategy();
        final Streams outboundLibraryStreams = context.outboundLibraryStreams();
        final ClusterableSubscription librarySubscription = outboundLibraryStreams.subscription();
        final Streams inboundLibraryStreams = context.inboundLibraryStreams();

        final ConnectionHandler handler = new ConnectionHandler(
            configuration,
            sessionIdStrategy,
            sessionIds,
            inboundLibraryStreams,
            idleStrategy,
            fixCounters,
            errorHandler);

        final SystemEpochClock clock = new SystemEpochClock();
        final GatewaySessions gatewaySessions = new GatewaySessions(
            clock,
            outboundLibraryStreams.gatewayPublication(idleStrategy),
            sessionIdStrategy,
            configuration.sessionCustomisationStrategy(),
            fixCounters,
            configuration.authenticationStrategy(),
            configuration.messageValidationStrategy(),
            configuration.sessionBufferSize(),
            configuration.sendingTimeWindowInMs());

        final Framer framer = new Framer(
            clock,
            timers.outboundTimer(),
            timers.sendTimer(),
            configuration, handler, librarySubscription,
            outboundLibraryStreams.subscription(), replaySubscription(),
            adminCommands, sessionIdStrategy, sessionIds,
            new SequenceNumberIndexReader(configuration.sentSequenceNumberBuffer()),
            new SequenceNumberIndexReader(configuration.receivedSequenceNumberBuffer()),
            gatewaySessions,
            context.inboundReplayQuery(),
            errorHandler,
            outboundLibraryStreams.gatewayPublication(idleStrategy),
            context.node()
        );
        framerRunner = new AgentRunner(idleStrategy, errorHandler, null, framer);
    }

    private Subscription replaySubscription()
    {
        return aeron.addSubscription(configuration.libraryAeronChannel(), OUTBOUND_REPLAY_STREAM);
    }

    private FixEngine launch()
    {
        startOnThread(framerRunner);
        context.start();
        start();
        return this;
    }

    /**
     * Close the engine down, including stopping other running threads.
     */
    public synchronized void close()
    {
        framerRunner.close();
        context.close();
        configuration.close();
        super.close();
    }
}
