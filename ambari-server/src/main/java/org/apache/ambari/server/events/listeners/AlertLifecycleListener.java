/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.events.listeners;

import java.util.Set;

import org.apache.ambari.server.EagerSingleton;
import org.apache.ambari.server.events.AlertDefinitionDeleteEvent;
import org.apache.ambari.server.events.AlertDefinitionRegistrationEvent;
import org.apache.ambari.server.events.publishers.AmbariEventPublisher;
import org.apache.ambari.server.state.alert.AggregateDefinitionMapping;
import org.apache.ambari.server.state.alert.AlertDefinition;
import org.apache.ambari.server.state.alert.AlertDefinitionHash;
import org.apache.ambari.server.state.alert.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * The {@link AlertLifecycleListener} handles events that are part of the alert
 * infrastructure lifecycle such as definition registration events.
 */
@Singleton
@EagerSingleton
public class AlertLifecycleListener {
  /**
   * Logger.
   */
  private static Logger LOG = LoggerFactory.getLogger(AlertLifecycleListener.class);

  /**
   * Used for quick lookups of aggregate alerts.
   */
  @Inject
  private AggregateDefinitionMapping m_aggregateMapping;

  /**
   * Invalidates hosts so that they can receive updated alert definition
   * commands.
   */
  @Inject
  private Provider<AlertDefinitionHash> m_alertDefinitionHash;

  /**
   * Constructor.
   *
   * @param publisher
   */
  @Inject
  public AlertLifecycleListener(AmbariEventPublisher publisher) {
    publisher.register(this);
  }

  /**
   * Handles {@link AlertDefinitionRegistrationEvent} by performing the
   * following tasks:
   * <ul>
   * <li>Registration with {@link AggregateDefinitionMapping}</li>
   * </ul>
   *
   * @param event
   *          the event being handled.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void onAmbariEvent(AlertDefinitionRegistrationEvent event) {
    AlertDefinition definition = event.getDefinition();

    LOG.debug("Registering alert definition {}", definition);

    if (definition.getSource().getType() == SourceType.AGGREGATE) {
      m_aggregateMapping.registerAggregate(event.getClusterId(), definition);
    }
  }

  /**
   * Handles {@link AlertDefinitionDeleteEvent} by performing the following
   * tasks:
   * <ul>
   * <li>Removal from with {@link AggregateDefinitionMapping}</li>
   * <li>{@link AlertDefinitionHash} invalidation</li>
   * </ul>
   *
   * @param event
   *          the event being handled.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void onAmbariEvent(AlertDefinitionDeleteEvent event) {
    AlertDefinition definition = event.getDefinition();

    LOG.debug("Removing alert definition {}", definition);

    if (null == definition) {
      return;
    }

    m_aggregateMapping.removeAssociatedAggregate(event.getClusterId(),
        definition.getName());

    AlertDefinitionHash hashHelper = m_alertDefinitionHash.get();
    Set<String> invalidatedHosts = hashHelper.invalidateHosts(definition);

    hashHelper.enqueueAgentCommands(definition.getClusterId(),
        invalidatedHosts);
  }
}
