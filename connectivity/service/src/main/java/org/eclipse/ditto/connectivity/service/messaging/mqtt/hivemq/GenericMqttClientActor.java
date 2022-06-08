/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq;

import java.text.MessageFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import org.eclipse.ditto.base.model.common.ConditionChecker;
import org.eclipse.ditto.base.model.headers.DittoHeaders;
import org.eclipse.ditto.connectivity.api.BaseClientState;
import org.eclipse.ditto.connectivity.model.Connection;
import org.eclipse.ditto.connectivity.model.signals.commands.modify.TestConnection;
import org.eclipse.ditto.connectivity.service.config.MqttConfig;
import org.eclipse.ditto.connectivity.service.messaging.BaseClientActor;
import org.eclipse.ditto.connectivity.service.messaging.BaseClientData;
import org.eclipse.ditto.connectivity.service.messaging.backoff.RetryTimeoutStrategy;
import org.eclipse.ditto.connectivity.service.messaging.internal.ClientConnected;
import org.eclipse.ditto.connectivity.service.messaging.internal.ClientDisconnected;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.MqttSpecificConfig;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.client.ClientRole;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.client.GenericMqttClient;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.client.GenericMqttClientDisconnectedListener;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.client.GenericMqttClientFactory;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.client.HiveMqttClientProperties;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.client.NoMqttConnectionException;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.consuming.MqttConsumerActor;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.consuming.ReconnectConsumerClient;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.publishing.MqttPublisherActor;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.subscribing.MqttSubscriber;
import org.eclipse.ditto.connectivity.service.messaging.mqtt.hivemq.subscribing.SubscribeResult;
import org.eclipse.ditto.connectivity.service.util.ConnectivityMdcEntryKey;
import org.eclipse.ditto.internal.models.signal.SignalInformationPoint;

import com.hivemq.client.mqtt.MqttClientConfig;
import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.datatypes.MqttClientIdentifier;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.typesafe.config.Config;

import akka.NotUsed;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Status;
import akka.japi.pf.FSMStateFunctionBuilder;
import akka.pattern.Patterns;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import scala.concurrent.ExecutionContextExecutor;
import scala.util.Success;
import scala.util.Try;

/**
 * Actor for handling connection to an MQTT broker for protocol versions 3 or 5.
 */
public final class GenericMqttClientActor extends BaseClientActor {

    private final MqttConfig mqttConfig;
    private final MqttSpecificConfig mqttSpecificConfig;

    @Nullable private GenericMqttClient genericMqttClient;
    private final AtomicBoolean automaticReconnect;
    @Nullable private ActorRef publishingActorRef;
    private final List<ActorRef> mqttConsumerActorRefs;

    @SuppressWarnings("java:S1144")
    private GenericMqttClientActor(final Connection connection,
            final ActorRef proxyActor,
            final ActorRef connectionActor,
            final DittoHeaders dittoHeaders,
            final Config connectivityConfigOverwrites) {

        super(connection, proxyActor, connectionActor, dittoHeaders, connectivityConfigOverwrites);

        final var connectivityConfig = connectivityConfig();
        final var connectionConfig = connectivityConfig.getConnectionConfig();
        mqttConfig = connectionConfig.getMqttConfig();

        mqttSpecificConfig = MqttSpecificConfig.fromConnection(connection, mqttConfig);

        genericMqttClient = null;
        automaticReconnect = new AtomicBoolean(true);
        publishingActorRef = null;
        mqttConsumerActorRefs = new ArrayList<>();
    }

    /**
     * Returns the {@code Props} for creating a {@code GenericMqttClientActor} with the specified arguments.
     *
     * @param mqttConnection the MQTT connection.
     * @param proxyActor the actor used to send signals into the Ditto cluster.
     * @param connectionActor the connection persistence actor which creates the returned client actor.
     * @param dittoHeaders headers of the command that caused the returned client actor to be created.
     * @param connectivityConfigOverwrites the overwrites of the connectivity config for the given connection.
     * @return the Props.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public static Props props(final Connection mqttConnection,
            final ActorRef proxyActor,
            final ActorRef connectionActor,
            final DittoHeaders dittoHeaders,
            final Config connectivityConfigOverwrites) {

        return Props.create(GenericMqttClientActor.class,
                ConditionChecker.checkNotNull(mqttConnection, "mqttConnection"),
                ConditionChecker.checkNotNull(proxyActor, "proxyActor"),
                ConditionChecker.checkNotNull(connectionActor, "connectionActor"),
                ConditionChecker.checkNotNull(dittoHeaders, "dittoHeaders"),
                ConditionChecker.checkNotNull(connectivityConfigOverwrites, "connectivityConfigOverwrites"));
    }

    @Override
    protected FSMStateFunctionBuilder<BaseClientState, BaseClientData> inConnectingState() {
        final FSMStateFunctionBuilder<BaseClientState, BaseClientData> result;
        if (isReconnectForRedelivery()) {
            result = super.inConnectingState()
                    .event(ReconnectConsumerClient.class, this::scheduleConsumerClientReconnect)
                    .eventEquals(Control.RECONNECT_CONSUMER_CLIENT, this::reconnectConsumerClient);
        } else {
            result = super.inConnectingState();
        }
        return result;
    }

    private boolean isReconnectForRedelivery() {
        return mqttSpecificConfig.reconnectForRedelivery();
    }

    private State<BaseClientState, BaseClientData> scheduleConsumerClientReconnect(
            final ReconnectConsumerClient reconnectConsumerClient,
            final BaseClientData baseClientData
    ) {
        final var trigger = Control.RECONNECT_CONSUMER_CLIENT;
        if (isTimerActive(trigger.name())) {
            logger.debug("Timer <{}> is active, thus not scheduling reconnecting consumer client again.",
                    trigger.name());
        } else {
            final var reconnectForRedeliveryDelay = reconnectConsumerClient.getReconnectDelay();
            logger.info("Scheduling reconnecting of consumer client in <{}>.", reconnectForRedeliveryDelay);
            startSingleTimer(trigger.name(), trigger, reconnectForRedeliveryDelay.getDuration());
        }
        return stay();
    }

    private State<BaseClientState, BaseClientData> reconnectConsumerClient(final Control control,
            final BaseClientData baseClientData) {

        if (null != genericMqttClient) {
            enableAutomaticReconnect();
            genericMqttClient.disconnectClientRole(ClientRole.CONSUMER);
        }
        return stay();
    }

    @Override
    protected FSMStateFunctionBuilder<BaseClientState, BaseClientData> inConnectedState() {
        final FSMStateFunctionBuilder<BaseClientState, BaseClientData> result;
        if (isReconnectForRedelivery()) {
            result = super.inConnectedState()
                    .event(ReconnectConsumerClient.class, this::scheduleConsumerClientReconnect)
                    .eventEquals(Control.RECONNECT_CONSUMER_CLIENT, this::reconnectConsumerClient);
        } else {
            result = super.inConnectedState();
        }
        return result;
    }

    @Override
    protected CompletionStage<Status.Status> doTestConnection(final TestConnection testConnectionCmd) {
        final var connection = testConnectionCmd.getConnection();
        return tryToGetHiveMqttClientPropertiesForConnectionTest(connection)
                .map(hiveMqttClientProperties -> getConnectionTester(hiveMqttClientProperties, testConnectionCmd))
                .map(ConnectionTester::testConnection)
                .fold(
                        error -> {
                            logger.withCorrelationId(testConnectionCmd)
                                    .withMdcEntry(ConnectivityMdcEntryKey.CONNECTION_ID, connection.getId())
                                    .info("Test for connection <{}> at <{}> failed.",
                                            connection.getId(),
                                            connection.getUri());
                            connectionLogger.failure("Connection test failed: {0}", error.getMessage());
                            return CompletableFuture.completedFuture(new Status.Failure(error));
                        },
                        statusCompletionStage -> statusCompletionStage
                );
    }

    private Try<HiveMqttClientProperties> tryToGetHiveMqttClientPropertiesForConnectionTest(final Connection connection) {
        try {
            return new Success<>(HiveMqttClientProperties.builder()
                    .withMqttConnection(connection)
                    .withConnectivityConfig(connectivityConfig())
                    .withMqttSpecificConfig(mqttSpecificConfig)
                    .withSshTunnelStateSupplier(this::getSshTunnelState)
                    .withConnectionLogger(connectionLogger)
                    .withActorUuid(actorUuid)
                    .build());
        } catch (final NoMqttConnectionException e) {
            return new scala.util.Failure<>(e);
        }
    }

    private ConnectionTester getConnectionTester(final HiveMqttClientProperties hiveMqttClientProperties,
            final TestConnection testConnectionCmd) {

        return ConnectionTester.builder()
                .withHiveMqttClientProperties(hiveMqttClientProperties)
                .withInboundMappingSink(getInboundMappingSink())
                .withConnectivityStatusResolver(connectivityStatusResolver)
                .withChildActorNanny(childActorNanny)
                .withActorSystemProvider(getContext().getSystem())
                .withCorrelationId(SignalInformationPoint.getCorrelationId(testConnectionCmd).orElse(null))
                .build();
    }

    @Override
    protected void cleanupResourcesForConnection() {
        mqttConsumerActorRefs.forEach(this::stopChildActor);
        stopChildActor(publishingActorRef);
        if (null != genericMqttClient) {
            disableAutomaticReconnect();
            genericMqttClient.disconnect();
        }

        genericMqttClient = null;
        publishingActorRef = null;
        mqttConsumerActorRefs.clear();
    }

    private void disableAutomaticReconnect() {
        automaticReconnect.set(false);
    }

    @Override
    protected void doConnectClient(final Connection connection, @Nullable final ActorRef origin) {
        if (null == genericMqttClient) {
            genericMqttClient = GenericMqttClientFactory.getProductiveGenericMqttClient(
                    getHiveMqttClientPropertiesOrThrow(connection)
            );
            enableAutomaticReconnect();
        }
        Patterns.pipe(
                genericMqttClient.connect().thenApply(aVoid -> MqttClientConnected.of(origin)),
                getContextDispatcher()
        ).to(getSelf());
    }

    private HiveMqttClientProperties getHiveMqttClientPropertiesOrThrow(final Connection connection) {
        try {
            return HiveMqttClientProperties.builder()
                    .withMqttConnection(connection)
                    .withConnectivityConfig(connectivityConfig())
                    .withMqttSpecificConfig(mqttSpecificConfig)
                    .withSshTunnelStateSupplier(this::getSshTunnelState)
                    .withConnectionLogger(connectionLogger)
                    .withActorUuid(actorUuid)
                    .withClientConnectedListener((context, clientRole) -> logger.info("Connected client <{}>.",
                            getClientId(clientRole, getMqttClientIdentifierOrNull(context.getClientConfig()))))
                    .withClientDisconnectedListener(getClientDisconnectedListener())
                    .build();
        } catch (final NoMqttConnectionException e) {

            // Let the supervisor strategy take care. Should not happen anyway.
            throw new IllegalArgumentException(e);
        }
    }

    @Nullable
    private static MqttClientIdentifier getMqttClientIdentifierOrNull(final MqttClientConfig mqttClientConfig) {
        return mqttClientConfig.getClientIdentifier().orElse(null);
    }

    private static String getClientId(final ClientRole clientRole,
            @Nullable final MqttClientIdentifier mqttClientIdentifier) {

        return MessageFormat.format("{0}:{1}", clientRole, mqttClientIdentifier);
    }

    private GenericMqttClientDisconnectedListener getClientDisconnectedListener() {
        return (context, clientRole) -> {
            final var mqttClientReconnector = context.getReconnector();
            final var retryTimeoutStrategy = getRetryTimeoutStrategy();

            if (0 == mqttClientReconnector.getAttempts()) {
                retryTimeoutStrategy.reset();
            }

            final var clientId = getClientId(clientRole, getMqttClientIdentifierOrNull(context.getClientConfig()));
            if (isMqttClientInConnectingState(context.getClientConfig())) {

                /*
                 * If the client is in initial CONNECTING state (i.e. was never
                 * connected, not reconnecting), we disable the automatic
                 * reconnect because the client would continue to connect and
                 * the caller would never see the cause why the connection
                 * failed.
                 */
                logger.info("Initial connect of client <{}> failed. Disabling automatic reconnect.", clientId);
                mqttClientReconnector.reconnect(false);
            } else {
                final var mqttDisconnectSource = context.getSource();
                final var reconnect = isReconnect();
                final var reconnectDelay = getReconnectDelay(retryTimeoutStrategy, mqttDisconnectSource);
                logger.info("Client <{}> disconnected by <{}>.", clientId, mqttDisconnectSource);
                if (reconnect) {
                    logger.info("Reconnecting client <{}> with current tries <{}> and a delay of <{}>.",
                            clientId,
                            retryTimeoutStrategy.getCurrentTries(),
                            reconnectDelay);
                } else {
                    logger.info("Not reconnecting client <{}>.", clientId);
                }
                mqttClientReconnector.delay(reconnectDelay.toMillis(), TimeUnit.MILLISECONDS);
                mqttClientReconnector.reconnect(reconnect);
            }
        };
    }

    private RetryTimeoutStrategy getRetryTimeoutStrategy() {
        final var reconnectBackOffConfig = mqttConfig.getReconnectBackOffConfig();
        return RetryTimeoutStrategy.newDuplicationRetryTimeoutStrategy(reconnectBackOffConfig.getTimeoutConfig());
    }

    private static boolean isMqttClientInConnectingState(final MqttClientConfig mqttClientConfig) {
        return MqttClientState.CONNECTING == mqttClientConfig.getState();
    }

    private boolean isReconnect() {
        final var connection = connection();
        return connection.isFailoverEnabled() && automaticReconnect.get();
    }

    private Duration getReconnectDelay(final RetryTimeoutStrategy retryTimeoutStrategy,
            final MqttDisconnectSource mqttDisconnectSource) {

        final Duration result;
        final var retryTimeoutReconnectDelay = retryTimeoutStrategy.getNextTimeout();
        if (MqttDisconnectSource.SERVER == mqttDisconnectSource) {
            final var reconnectDelayForBrokerInitiatedDisconnect =
                    mqttConfig.getReconnectMinTimeoutForMqttBrokerInitiatedDisconnect();
            if (0 <= retryTimeoutReconnectDelay.compareTo(reconnectDelayForBrokerInitiatedDisconnect)) {
                result = retryTimeoutReconnectDelay;
            } else {
                result = reconnectDelayForBrokerInitiatedDisconnect;
            }
        } else {
            result = retryTimeoutReconnectDelay;
        }
        return result;
    }

    private void enableAutomaticReconnect() {
        automaticReconnect.set(true);
    }

    private ExecutionContextExecutor getContextDispatcher() {
        final var actorContext = getContext();
        return actorContext.getDispatcher();
    }

    @Override
    protected void doDisconnectClient(final Connection connection,
            @Nullable final ActorRef origin,
            final boolean shutdownAfterDisconnect) {

        final CompletionStage<Void> disconnectFuture;
        if (null == genericMqttClient) {
            disconnectFuture = CompletableFuture.completedFuture(null);
        } else {
            disableAutomaticReconnect();
            disconnectFuture = genericMqttClient.disconnect();
        }

        Patterns.pipe(
                disconnectFuture.handle((aVoid, throwable) -> ClientDisconnected.of(origin, shutdownAfterDisconnect)),
                getContextDispatcher()
        ).to(getSelf(), origin);
    }

    @Nullable
    @Override
    protected ActorRef getPublisherActor() {
        return publishingActorRef;
    }

    @Override
    protected CompletionStage<Status.Status> startPublisherActor() {
        final CompletionStage<Status.Status> result;
        if (null != genericMqttClient) {
            publishingActorRef = startChildActorConflictFree(
                    MqttPublisherActor.class.getSimpleName(),
                    MqttPublisherActor.propsProcessing(connection(),
                            connectivityStatusResolver,
                            connectivityConfig(),
                            genericMqttClient)
            );
            result = CompletableFuture.completedFuture(DONE);
        } else {
            result = CompletableFuture.failedFuture(
                    new IllegalStateException("Cannot start publisher actor because generic MQTT client is null.")
            );
        }
        return result;
    }

    @Override
    protected CompletionStage<Status.Status> startConsumerActors(@Nullable final ClientConnected clientConnected) {
        return subscribe()
                .thenCompose(this::handleSourceSubscribeResults)
                .thenApply(actorRefs -> {
                    mqttConsumerActorRefs.addAll(actorRefs);
                    return DONE;
                });
    }

    private CompletionStage<Source<SubscribeResult, NotUsed>> subscribe() {
        final CompletionStage<Source<SubscribeResult, NotUsed>> result;
        if (null != genericMqttClient) {
            final var subscriber = MqttSubscriber.newInstance(genericMqttClient);
            result = CompletableFuture.completedFuture(
                    subscriber.subscribeForConnectionSources(connection().getSources())
            );
        } else {
            result = CompletableFuture.failedFuture(new IllegalStateException(
                    "Cannot subscribe for connection sources as generic MQTT client is not yet initialised."
            ));
        }
        return result;
    }

    private CompletionStage<List<ActorRef>> handleSourceSubscribeResults(
            final Source<SubscribeResult, NotUsed> sourceSubscribeResults
    ) {
        return sourceSubscribeResults.map(this::startMqttConsumerActorOrThrow)
                .toMat(Sink.seq(), Keep.right())
                .run(getContext().getSystem());
    }

    private ActorRef startMqttConsumerActorOrThrow(final SubscribeResult subscribeResult) {
        if (subscribeResult.isSuccess()) {
            return startChildActorConflictFree(
                    MqttConsumerActor.class.getSimpleName(),
                    MqttConsumerActor.propsProcessing(connection(),
                            getInboundMappingSink(),
                            subscribeResult.getConnectionSource(),
                            connectivityStatusResolver,
                            connectivityConfig(),
                            subscribeResult.getMqttPublishSourceOrThrow())
            );
        } else {

            // TODO jff really no entry in connection log?
            throw subscribeResult.getErrorOrThrow();
        }
    }

    /*
     * For MQTT connections only one consumer actor for all addresses is started,
     * i.e. one consumer actor per connection source.
     */
    @Override
    protected int determineNumberOfConsumers() {
        return connectionSources()
                .mapToInt(org.eclipse.ditto.connectivity.model.Source::getConsumerCount)
                .sum();
    }

    private Stream<org.eclipse.ditto.connectivity.model.Source> connectionSources() {
        return connection().getSources().stream();
    }

    /*
     * For MQTT connections only one Consumer Actor for all addresses is started,
     * i.e. one consumer actor per connection source.
     */
    @Override
    protected Stream<String> getSourceAddresses() {
        return connectionSources()
                .map(org.eclipse.ditto.connectivity.model.Source::getAddresses)
                .map(sourceAddresses -> String.join(";", sourceAddresses));
    }

    @Override
    public void postStop() {
        logger.info("Actor stopped, stopping clients.");
        if (null != genericMqttClient) {
            disableAutomaticReconnect();
            genericMqttClient.disconnect();
        }
        super.postStop();
    }

    private enum Control {

        RECONNECT_CONSUMER_CLIENT;

    }

}
