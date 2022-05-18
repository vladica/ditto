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
package org.eclipse.ditto.base.service;

import java.util.List;

import org.eclipse.ditto.internal.utils.akka.AkkaClassLoader;

import akka.actor.AbstractExtensionId;
import akka.actor.ActorSystem;
import akka.actor.ExtendedActorSystem;
import akka.actor.Extension;

/**
 * Extension Point for extending Ditto via Akka Extensions to provide custom functionality to different
 * aspects of the service.
 *
 * @since 3.0.0
 */
public interface DittoExtensionPoint extends Extension {

    /**
     * @param <T> the class of the extension for which an implementation should be loaded.
     */
    abstract class ExtensionId<T extends Extension> extends AbstractExtensionId<T> {

        private final Class<T> parentClass;

        /**
         * Returns the {@code ExtensionId} for the implementation that should be loaded.
         *
         * @param parentClass the class of the extensions for which an implementation should be loaded.
         */
        public ExtensionId(final Class<T> parentClass) {
            this.parentClass = parentClass;
        }

        @Override
        public T createExtension(final ExtendedActorSystem system) {
            return AkkaClassLoader.instantiate(system, parentClass,
                    getImplementation(system),
                    List.of(ActorSystem.class),
                    List.of(system));
        }

        private String getImplementation(final ExtendedActorSystem actorSystem) {
            return actorSystem.settings().config().getString(getConfigPath());
        }

        protected abstract String getConfigPath();

    }

}
