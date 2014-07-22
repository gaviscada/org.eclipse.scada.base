/*******************************************************************************
 * Copyright (c) 2014 IBH SYSTEMS GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBH SYSTEMS GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.scada.base.extractor.mqtt.input;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.TimerPingSender;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.eclipse.scada.base.extractor.input.AbstractInput;
import org.eclipse.scada.base.extractor.input.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MqttInput extends AbstractInput
{
    private final static Logger logger = LoggerFactory.getLogger ( MqttInput.class );

    private final String serverUri;

    private final String clientId;

    private MqttAsyncClient client;

    private final ScheduledExecutorService executor;

    private boolean started;

    private final String topic;

    private final int qos;

    private final MqttCallback callback = new MqttCallback () {

        @Override
        public void messageArrived ( final String topic, final MqttMessage msg ) throws Exception
        {
            handleMessage ( topic, msg );
        }

        @Override
        public void deliveryComplete ( final IMqttDeliveryToken token )
        {
        }

        @Override
        public void connectionLost ( final Throwable e )
        {
            handleDisconnected ( e );
        }
    };

    public MqttInput ( final ScheduledExecutorService executor, final String serverUri, final String clientId, final String topic, final int qos )
    {
        super ( executor );
        this.executor = executor;
        this.serverUri = serverUri;

        this.clientId = makeClientId ( clientId );

        this.topic = topic;
        this.qos = qos;
    }

    protected void handleMessage ( final String topic, final MqttMessage msg )
    {
        logger.debug ( "Got message - topic: {}, message: {}", topic, msg );
        fireData ( new MqttData ( msg ) );
    }

    private String makeClientId ( final String clientId )
    {
        if ( clientId != null )
        {
            return clientId;
        }

        return MqttAsyncClient.generateClientId ();
    }

    @Override
    public synchronized void start ()
    {
        if ( this.started )
        {
            return;
        }

        this.started = true;
        triggerConnect ();
    }

    @Override
    public synchronized void stop ()
    {
        if ( !this.started )
        {
            return;
        }

        this.started = false;
        if ( this.client != null )
        {
            try
            {
                this.client.disconnect ().waitForCompletion ();
            }
            catch ( final MqttException e )
            {
                logger.warn ( "Failed to close MQTT Client", e );
            }
            finally
            {
                this.client = null;
                fireDisposed ();
            }
        }
    }

    protected synchronized void triggerConnect ()
    {
        if ( !this.started )
        {
            return;
        }

        try
        {
            this.client = new MqttAsyncClient ( this.serverUri, this.clientId, new MemoryPersistence (), new TimerPingSender () );
            this.client.setCallback ( this.callback );
            this.client.connect ( null, new IMqttActionListener () {

                @Override
                public void onSuccess ( final IMqttToken token )
                {
                    handleConnected ();
                }

                @Override
                public void onFailure ( final IMqttToken token, final Throwable e )
                {
                    handleDisconnected ( e );
                }
            } );
        }
        catch ( final MqttException e )
        {
            handleDisconnected ( e );
        }
    }

    protected synchronized void handleDisconnected ( final Throwable e )
    {
        logger.info ( "Disconnected from: {}", this.serverUri );
        if ( e != null )
        {
            logger.info ( "Disconnect reason", e );
        }

        try
        {
            this.client.close ();
        }
        catch ( final Exception e2 )
        {
            logger.warn ( "Failed to close", e2 );
        }
        this.client = null;

        fireData ( new Data ( null, e ) );
        if ( this.started )
        {
            this.executor.schedule ( new Runnable () {

                @Override
                public void run ()
                {
                    triggerConnect ();
                }
            }, 10, TimeUnit.SECONDS );
        }
    }

    protected synchronized void handleConnected ()
    {
        logger.info ( "Connected to: {}", this.serverUri );
        try
        {
            logger.debug ( "Subscribe to: {} (qos: {})", this.topic, this.qos );
            this.client.subscribe ( this.topic, this.qos );
        }
        catch ( final MqttException e )
        {
            handleDisconnected ( e );
        }
    }
}