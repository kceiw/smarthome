/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.core.thing.firmware;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;

import org.eclipse.smarthome.core.events.Event;
import org.eclipse.smarthome.core.events.EventPublisher;
import org.eclipse.smarthome.core.i18n.I18nProvider;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.eclipse.smarthome.core.thing.binding.firmware.FirmwareUID;
import org.eclipse.smarthome.core.thing.binding.firmware.FirmwareUpdateHandler;
import org.eclipse.smarthome.core.thing.binding.firmware.ProgressCallback;
import org.eclipse.smarthome.core.thing.binding.firmware.ProgressStep;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.google.common.base.Preconditions;

/**
 * The callback implementation for the {@link ProgressCallback}.
 *
 * @author Thomas Höfer - Initial contribution
 * @author Christoph Knauf - Introduced pending, canceled, update and InternalState
 */
final class ProgressCallbackImpl implements ProgressCallback {

    private static String UPDATE_CANCELED_MESSAGE_KEY = "update-canceled";

    /**
     * Handler instance is needed to retrieve the error messages from the correct bundle.
     */
    private final FirmwareUpdateHandler firmwareUpdateHandler;
    private final EventPublisher eventPublisher;
    private final I18nProvider i18nProvider;
    private final ThingUID thingUID;
    private final FirmwareUID firmwareUID;
    private final Locale locale;

    private Collection<ProgressStep> sequence;
    private Iterator<ProgressStep> progressIterator;
    private ProgressStep current;
    private Integer progress;

    private enum InternalState {
        FINISHED,
        PENDING,
        RUNNING,
        INITIALIZED
    };

    private InternalState state;

    ProgressCallbackImpl(FirmwareUpdateHandler firmwareUpdateHandler, EventPublisher eventPublisher,
            I18nProvider i18nProvider, ThingUID thingUID, FirmwareUID firmwareUID, Locale locale) {
        this.firmwareUpdateHandler = firmwareUpdateHandler;
        this.eventPublisher = eventPublisher;
        this.i18nProvider = i18nProvider;
        this.thingUID = thingUID;
        this.firmwareUID = firmwareUID;
        this.locale = locale;
        this.progress = null;
    }

    @Override
    public void defineSequence(ProgressStep... sequence) {
        Preconditions.checkArgument(sequence != null && sequence.length > 0, "Sequence must not be null or empty.");
        this.sequence = Collections.unmodifiableCollection(Arrays.asList(sequence));
        progressIterator = this.sequence.iterator();
        this.state = InternalState.INITIALIZED;
    }

    @Override
    public void next() {
        Preconditions.checkState(this.state != InternalState.FINISHED, "Update is finished.");
        if (this.state == InternalState.PENDING) {
            state = InternalState.RUNNING;
            postProgressInfoEvent();
        } else if (progressIterator.hasNext()) {
            state = InternalState.RUNNING;
            this.current = progressIterator.next();
            postProgressInfoEvent();
        } else {
            state = InternalState.FINISHED;
            throw new IllegalStateException("There is no further progress step to be executed.");
        }
    }

    @Override
    public void failed(String errorMessageKey, Object... arguments) {
        Preconditions.checkState(this.state != InternalState.FINISHED, "Update is finished.");
        Preconditions.checkArgument(errorMessageKey != null && !errorMessageKey.isEmpty(),
                "The error message key must not be null or empty.");
        this.state = InternalState.FINISHED;
        String errorMessage = getMessage(firmwareUpdateHandler.getClass(), errorMessageKey, arguments);
        postResultInfoEvent(FirmwareUpdateResult.ERROR, errorMessage);
    }

    @Override
    public void success() {
        Preconditions.checkState(this.state != InternalState.FINISHED, "Update is finished.");
        Preconditions.checkState((this.progress != null && this.progress == 100) || (this.progressIterator!=null && !progressIterator.hasNext()),
                "Update can't be successfully finished until progress is 100% or last progress step is reached");
        this.state = InternalState.FINISHED;
        postResultInfoEvent(FirmwareUpdateResult.SUCCESS, null);
    }

    @Override
    public void pending() {
        Preconditions.checkState(this.state != InternalState.FINISHED, "Update is finished.");
        this.state = InternalState.PENDING;
        postProgressInfoEvent();
    }

    @Override
    public void canceled() {
        Preconditions.checkState(this.state != InternalState.FINISHED, "Update is finished.");
        this.state = InternalState.FINISHED;
        String cancelMessage = getMessage(this.getClass(), UPDATE_CANCELED_MESSAGE_KEY);
        postResultInfoEvent(FirmwareUpdateResult.CANCELED, cancelMessage);
    }

    @Override
    public void update(int progress) {
        Preconditions.checkState(this.state != InternalState.FINISHED, "Update is finished.");
        Preconditions.checkArgument(progress >= 0 && progress <= 100, "The progress must be between 0 and 100.");
        if (this.progress == null) {
            updateProgress(progress);
        } else if (progress < this.progress) {
            throw new IllegalArgumentException("The new progress must not be smaller than the old progress.");
        } else if (this.progress != progress) {
            updateProgress(progress);
        }
    }

    private void updateProgress(int progress) {
        this.progress = progress;
        this.state = InternalState.RUNNING;
        postProgressInfoEvent();
    }

    void failedInternal(String errorMessageKey) {
        this.state = InternalState.FINISHED;
        String errorMessage = getMessage(ProgressCallbackImpl.class, errorMessageKey, new Object[] {});
        postResultInfoEvent(FirmwareUpdateResult.ERROR, errorMessage);
    }

    private String getMessage(Class<?> clazz, String errorMessageKey, Object... arguments) {
        Bundle bundle = FrameworkUtil.getBundle(clazz);
        String errorMessage = i18nProvider.getText(bundle, errorMessageKey, null, locale, arguments);
        return errorMessage;
    }

    private void postResultInfoEvent(FirmwareUpdateResult result, String message) {
        post(FirmwareEventFactory.createFirmwareUpdateResultInfoEvent(new FirmwareUpdateResultInfo(result, message),
                thingUID));
    }

    private void postProgressInfoEvent() {
        if (this.progress == null) {
            post(FirmwareEventFactory.createFirmwareUpdateProgressInfoEvent(new FirmwareUpdateProgressInfo(firmwareUID,
                    getCurrentStep(), sequence, this.state == InternalState.PENDING), thingUID));
        } else {
            post(FirmwareEventFactory.createFirmwareUpdateProgressInfoEvent(new FirmwareUpdateProgressInfo(firmwareUID,
                    getCurrentStep(), sequence, this.state == InternalState.PENDING, progress), thingUID));
        }
    }

    private void post(Event event) {
        eventPublisher.post(event);
    }

    private ProgressStep getCurrentStep() {
        if (current != null) {
            return current;
        }
        if (sequence != null && progressIterator.hasNext()) {
            this.current = progressIterator.next();
            return current;
        }
        return null;
    }
}
