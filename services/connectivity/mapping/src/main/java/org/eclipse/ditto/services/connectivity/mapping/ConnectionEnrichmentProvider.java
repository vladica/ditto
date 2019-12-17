/*
 * Copyright (c) 2019 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.connectivity.mapping;

import java.util.Arrays;

import org.eclipse.ditto.model.connectivity.ConnectionId;
import org.eclipse.ditto.services.models.things.ThingEnrichingFacade;
import org.eclipse.ditto.services.utils.akka.AkkaClassLoader;

import com.typesafe.config.Config;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;

/**
 * Provider of {@link org.eclipse.ditto.services.models.things.ThingEnrichingFacade} to be loaded by reflection.
 * Implementations MUST have a public constructor taking the following parameters as arguments:
 * <ul>
 * <li>ActorSystem actorSystem: actor system in which this provider is loaded,</li>
 * <li>ActorRef commandHandler: recipient of retrieve-thing commands,</li>
 * <li>Config config: configuration for the facade provider.</li>
 * </ul>
 */
public interface ConnectionEnrichmentProvider {

    /**
     * Create a thing-enriching facade from the ID of a connection.
     *
     * @param connectionId the connection ID.
     * @return the facade.
     */
    ThingEnrichingFacade createFacade(ConnectionId connectionId);

    /**
     * Load a {@code ThingEnrichingFacadeProvider} dynamically according to the streaming configuration.
     *
     * @param actorSystem The actor system in which to load the facade provider class.
     * @param commandHandler The recipient of retrieve-thing commands.
     * @return The configured facade provider.
     */
    static ConnectionEnrichmentProvider load(final ActorSystem actorSystem, final ActorRef commandHandler) {

        final ConnectionEnrichmentConfig connectionEnrichmentConfig =
                DefaultConnectionEnrichmentConfig.forActorSystem(actorSystem);

        return AkkaClassLoader.instantiate(actorSystem, ConnectionEnrichmentProvider.class,
                connectionEnrichmentConfig.getProvider(),
                Arrays.asList(ActorSystem.class, ActorRef.class, Config.class),
                Arrays.asList(actorSystem, commandHandler, connectionEnrichmentConfig.getConfig())
        );
    }
}
