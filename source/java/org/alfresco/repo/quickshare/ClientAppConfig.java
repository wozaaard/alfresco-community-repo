/*
 * Copyright (C) 2005-2016 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */

package org.alfresco.repo.quickshare;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.ApplicationEvent;
import org.springframework.extensions.surf.util.AbstractLifecycleBean;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * This class picks up all the loaded properties passed to it and uses a naming
 * convention to isolate the client's name and related values.
 * So, if a new client (e.g. MyClientName) is required to send shared-link email, then the following
 * needs to be put into a properties file.
 * <ul>
 * <li>quickshare.client.MyClientName.sharedLinkBaseUrl=http://localhost:8080/MyClientName/s</li>
 * <li>quickshare.client.MyClientName.templateAssetsUrl=http://localhost:8080/MyClientName/assets</li>
 * </ul>
 * The default property file is <b>alfresco/quickshare/quickshare-clients.properties</b> which
 * could be overridden by <b>alfresco-global</b> properties file.
 *
 * @author Jamal Kaabi-Mofrad
 */
public class ClientAppConfig extends AbstractLifecycleBean
{
    private static final Log logger = LogFactory.getLog(ClientAppConfig.class);

    public static final String PREFIX = "quickshare.client.";
    public static final String PROP_SHARED_LINK_BASE_URL = "sharedLinkBaseUrl";
    public static final String PROP_TEMPLATE_ASSETS_URL = "templateAssetsUrl";

    private Properties defaultProperties;
    private Properties globalProperties;

    private ConcurrentMap<String, ClientApp> clients = new ConcurrentHashMap<>();

    public ClientAppConfig()
    {
    }

    public void setDefaultProperties(Properties defaultProperties)
    {
        this.defaultProperties = defaultProperties;
    }

    public void setGlobalProperties(Properties globalProperties)
    {
        this.globalProperties = globalProperties;
    }

    @Override
    protected void onBootstrap(ApplicationEvent event)
    {
        load();
        if (logger.isDebugEnabled())
        {
            logger.debug("All bootstrapped quickShare clients: " + clients);
        }
    }

    @Override
    protected void onShutdown(ApplicationEvent event)
    {
        // nothing to do
    }

    public void load()
    {
        Map<String, String> mergedProperties = getAndMergeProperties();
        Set<String> clientsNames = processPropertyKeys(mergedProperties);
        clients.putAll(processClients(clientsNames, mergedProperties));
    }

    public Map<String, ClientApp> getClients()
    {
        return Collections.unmodifiableMap(clients);
    }

    public ClientApp getClient(String name)
    {
        return clients.get(name);
    }

    public void setClient(ClientApp client)
    {
        if (client != null)
        {
            clients.put(client.getName(), client);
        }
    }

    public boolean removeClient(String name)
    {
        ClientApp client = clients.remove(name);
        return (client != null);
    }

    protected Set<String> processPropertyKeys(Map<String, String> allProps)
    {
        Set<String> clientsNames = new HashSet<>();
        for (String key : allProps.keySet())
        {
            String propKey = key;
            if (propKey.startsWith(PREFIX))
            {
                propKey = propKey.substring(PREFIX.length());
                // Find the client name
                int clientNameControlDot = propKey.indexOf('.');
                if (clientNameControlDot < 1)
                {
                    logMalformedPropertyKey(key);
                    continue;
                }

                int propNameLength = (propKey.length() - clientNameControlDot) - 1; // Length of characters between dots
                if (propNameLength < 1)
                {
                    logMalformedPropertyKey(key);
                    continue;
                }
                String clientName = propKey.substring(0, clientNameControlDot);
                String propName = propKey.substring((clientNameControlDot + 1));
                if (PROP_SHARED_LINK_BASE_URL.equals(propName) || PROP_TEMPLATE_ASSETS_URL.equals(propName))
                {
                    clientsNames.add(clientName);
                }
                else
                {
                    logMalformedPropertyKey(key);
                }
            }
            else
            {
                logMalformedPropertyKey(propKey);
            }
        }
        return clientsNames;
    }

    protected Map<String, ClientApp> processClients(Set<String> clientsNames, Map<String, String> allProps)
    {
        Map<String, ClientApp> clientApps = new HashMap<>(clientsNames.size());
        for (String name : clientsNames)
        {
            String propKey = getPropertyKey(name, PROP_SHARED_LINK_BASE_URL);
            String sharedLinkBaseUrl = allProps.get(propKey);
            if (StringUtils.isEmpty(sharedLinkBaseUrl))
            {
                logInvalidPropertyValue(propKey, sharedLinkBaseUrl);
                continue;
            }

            propKey = getPropertyKey(name, PROP_TEMPLATE_ASSETS_URL);
            String templateAssetsUrl = allProps.get(propKey);
            if (StringUtils.isEmpty(templateAssetsUrl))
            {
                logInvalidPropertyValue(propKey, templateAssetsUrl);
                continue;
            }
            // Create the client data
            ClientApp client = new ClientApp(name, sharedLinkBaseUrl, templateAssetsUrl);
            clientApps.put(name, client);
        }
        return clientApps;
    }

    protected Map<String, String> getAndMergeProperties()
    {
        Map<String, String> allProperties = new HashMap<>();
        for (String propKey : defaultProperties.stringPropertyNames())
        {
            allProperties.put(propKey, defaultProperties.getProperty(propKey));

        }

        //override default values from other property files
        for (String propKey : globalProperties.stringPropertyNames())
        {
            if (propKey.startsWith(PREFIX))
            {
                allProperties.put(propKey, globalProperties.getProperty(propKey));
            }
        }

        return allProperties;
    }

    private void logMalformedPropertyKey(String propName)
    {
        logger.warn("Ignoring quickShare client (malformed property key): " + propName);
    }

    private void logInvalidPropertyValue(String propName, String propValue)
    {
        logger.warn("Ignoring quickShare client (invalid value) [" + propValue + "] for the property:" + propName);
    }

    private String getPropertyKey(String clientName, String clientProp)
    {
        return PREFIX + clientName + '.' + clientProp;
    }

    public static class ClientApp
    {
        private final String name;
        private final String sharedLinkBaseUrl;
        private final String templateAssetsUrl;

        public ClientApp(String name, String sharedLinkBaseUrl, String templateAssetsUrl)
        {
            this.name = name;
            this.sharedLinkBaseUrl = sharedLinkBaseUrl;
            this.templateAssetsUrl = templateAssetsUrl;
        }

        public String getName()
        {
            return name;
        }

        public String getSharedLinkBaseUrl()
        {
            return sharedLinkBaseUrl;
        }

        public String getTemplateAssetsUrl()
        {
            return templateAssetsUrl;
        }

        @Override
        public boolean equals(Object o)
        {
            if (this == o)
            {
                return true;
            }
            if (!(o instanceof ClientApp))
            {
                return false;
            }

            ClientApp clientApp = (ClientApp) o;
            return getName().equals(clientApp.getName());
        }

        @Override
        public int hashCode()
        {
            return getName().hashCode();
        }

        @Override
        public String toString()
        {
            final StringBuilder sb = new StringBuilder(250);
            sb.append("ClientApp [name=").append(name)
                        .append(", sharedLinkBaseUrl=").append(sharedLinkBaseUrl)
                        .append(", templateAssetsUrl=").append(templateAssetsUrl)
                        .append(']');
            return sb.toString();
        }
    }
}
