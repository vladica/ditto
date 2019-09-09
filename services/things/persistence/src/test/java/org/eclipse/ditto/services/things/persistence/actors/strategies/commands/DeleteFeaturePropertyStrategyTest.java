/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.things.persistence.actors.strategies.commands;

import static org.eclipse.ditto.model.things.TestConstants.Feature.FLUX_CAPACITOR;
import static org.eclipse.ditto.model.things.TestConstants.Feature.FLUX_CAPACITOR_ID;
import static org.eclipse.ditto.model.things.TestConstants.Thing.THING_V2;
import static org.mutabilitydetector.unittesting.MutabilityAssert.assertInstancesOf;
import static org.mutabilitydetector.unittesting.MutabilityMatchers.areImmutable;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.things.Feature;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.services.utils.persistentactors.commands.CommandStrategy;
import org.eclipse.ditto.signals.commands.things.modify.DeleteFeatureProperty;
import org.eclipse.ditto.signals.commands.things.modify.DeleteFeaturePropertyResponse;
import org.eclipse.ditto.signals.events.things.FeaturePropertyDeleted;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Unit test for {@link DeleteFeaturePropertyStrategy}.
 */
public final class DeleteFeaturePropertyStrategyTest extends AbstractCommandStrategyTest {

    private static String featureId;
    private static JsonPointer propertyPointer;

    private DeleteFeaturePropertyStrategy underTest;

    @BeforeClass
    public static void initTestFixture() {
        featureId = FLUX_CAPACITOR_ID;
        propertyPointer = JsonFactory.newPointer("/target_year_2");
    }

    @Before
    public void setUp() {
        underTest = new DeleteFeaturePropertyStrategy();
    }

    @Test
    public void assertImmutability() {
        assertInstancesOf(DeleteFeaturePropertyStrategy.class, areImmutable());
    }

    @Test
    public void successfullyDeleteFeaturePropertyFromThing() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteFeatureProperty command =
                DeleteFeatureProperty.of(context.getEntityId(), featureId, propertyPointer, DittoHeaders.empty());

        assertModificationResult(underTest, THING_V2, command,
                FeaturePropertyDeleted.class,
                DeleteFeaturePropertyResponse.of(context.getEntityId(),
                        command.getFeatureId(), propertyPointer, command.getDittoHeaders()));
    }

    @Test
    public void deleteFeaturePropertyFromThingWithoutFeatures() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteFeatureProperty command =
                DeleteFeatureProperty.of(context.getEntityId(), featureId, propertyPointer, DittoHeaders.empty());
        final DittoRuntimeException expectedException =
                ExceptionFactory.featureNotFound(context.getEntityId(), command.getFeatureId(),
                        command.getDittoHeaders());

        assertErrorResult(underTest, THING_V2.removeFeatures(), command, expectedException);
    }

    @Test
    public void deleteFeaturePropertyFromThingWithoutThatFeature() {
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteFeatureProperty command =
                DeleteFeatureProperty.of(context.getEntityId(), featureId, propertyPointer, DittoHeaders.empty());
        final DittoRuntimeException expectedException =
                ExceptionFactory.featureNotFound(context.getEntityId(), command.getFeatureId(),
                        command.getDittoHeaders());

        assertErrorResult(underTest, THING_V2.removeFeature(featureId), command, expectedException);
    }

    @Test
    public void deleteFeaturePropertyFromFeatureWithoutProperties() {
        final Feature feature = FLUX_CAPACITOR.removeProperties();
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteFeatureProperty command =
                DeleteFeatureProperty.of(context.getEntityId(), feature.getId(), propertyPointer, DittoHeaders.empty());
        final DittoRuntimeException expectedException =
                ExceptionFactory.featurePropertyNotFound(context.getEntityId(), command.getFeatureId(),
                        propertyPointer, command.getDittoHeaders());

        assertErrorResult(underTest, THING_V2.setFeature(feature), command, expectedException);
    }

    @Test
    public void deleteFeaturePropertyFromFeatureWithoutThatProperty() {
        final Feature feature = FLUX_CAPACITOR.removeProperty(propertyPointer);
        final CommandStrategy.Context<ThingId> context = getDefaultContext();
        final DeleteFeatureProperty command =
                DeleteFeatureProperty.of(context.getEntityId(), feature.getId(), propertyPointer, DittoHeaders.empty());
        final DittoRuntimeException expectedException =
                ExceptionFactory.featurePropertyNotFound(context.getEntityId(), command.getFeatureId(),
                        propertyPointer, command.getDittoHeaders());

        assertErrorResult(underTest, THING_V2.setFeature(feature), command, expectedException);
    }

}
