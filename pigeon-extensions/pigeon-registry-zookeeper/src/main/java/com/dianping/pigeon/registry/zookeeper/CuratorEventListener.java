package com.dianping.pigeon.registry.zookeeper;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.framework.api.CuratorListener;
import org.apache.log4j.Logger;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher.Event.EventType;

import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.extension.ExtensionLoader;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.registry.exception.RegistryException;
import com.dianping.pigeon.registry.listener.DefaultServiceChangeListener;
import com.dianping.pigeon.registry.listener.ServiceChangeListener;
import com.dianping.pigeon.registry.util.Constants;

public class CuratorEventListener implements CuratorListener {

    private static Logger logger = LoggerLoader.getLogger(CuratorEventListener.class);
    
    private static final int ADDRESS = 1;
    private static final int WEIGHT = 2;
    private static final int EPHEMERAL_ADDRESS = 3;
    
    private ConfigManager configManager = ExtensionLoader.getExtension(ConfigManager.class);

    private CuratorRegistry registry;
    private CuratorClient client;
    
    private ServiceChangeListener serviceChangeListener = new DefaultServiceChangeListener();
    
    public CuratorEventListener(CuratorRegistry registry, CuratorClient client) {
        this.registry = registry;
        this.client = client;
    }
    
    @Override
    public void eventReceived(CuratorFramework client, CuratorEvent curatorEvent) throws Exception {
        WatchedEvent event = (curatorEvent == null ? null : curatorEvent.getWatchedEvent());
        
        if (event == null || (
            event.getType() != EventType.NodeCreated && 
            event.getType() != EventType.NodeDataChanged &&
            event.getType() != EventType.NodeDeleted && 
            event.getType() != EventType.NodeChildrenChanged)) {
            return;
        }

        if(logger.isInfoEnabled()) 
            logEvent(event);

        try {
            PathInfo pathInfo = parsePath(event.getPath());
            if (pathInfo == null) {
                logger.warn("Failed to parse path " + event.getPath());
                return;
            }

            if (pathInfo.type == ADDRESS) {
                addressChanged(pathInfo);
            } else if (pathInfo.type == WEIGHT) {
                weightChanged(pathInfo);
            } else if(pathInfo.type == EPHEMERAL_ADDRESS) {
                if(EventType.NodeCreated == event.getType()) {
                    this.client.watchChildren(event.getPath());
                    ephemeralAddressChanged(pathInfo);
                } else if(EventType.NodeDeleted == event.getType()) {
                    this.client.watch(event.getPath());
                } else if(EventType.NodeChildrenChanged == event.getType()) {
                    ephemeralAddressChanged(pathInfo);
                }
            }
        } catch (Throwable e) {
            logger.error("Error in ZookeeperWatcher.process()", e);
            return;
        }
    }
    
    private void logEvent(WatchedEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("zookeeper event received, type: ").append(event.getType()).
                append(", path: ").append(event.getPath());
        logger.info(sb);
    }

    /*
     * 1. Get newest value from ZK and watch again 2. Determine if changed
     * against cache 3. notify if changed 4. pay attention to group fallback
     * notification
     */
    private void addressChanged(PathInfo pathInfo) throws Exception {
        if (shouldNotify(pathInfo)) {
            String hosts = client.get(pathInfo.path);
            logger.info("Service address changed, path " + pathInfo.path + " value " + hosts);
            List<String[]> hostDetail = Utils.getServiceIpPortList(hosts);
            serviceChangeListener.onServiceHostChange(pathInfo.serviceName, hostDetail);
        } else {
            // Watch again
            client.watch(pathInfo.path);
        }
    }

    /*
     * 1. Get newest value from ZK and watch again 2. Determine if changed
     * against cache 3. notify if changed 4. pay attention to group fallback
     * notification
     */
    private void ephemeralAddressChanged(PathInfo pathInfo) throws Exception {
        if (shouldNotify(pathInfo)) {
            String hosts = registry.getServiceAddress(pathInfo.serviceName, pathInfo.group);
            logger.info("ephemeral service address changed, path " + pathInfo.path + " value " + hosts);
            List<String[]> hostDetail = Utils.getServiceIpPortList(hosts);
            serviceChangeListener.onServiceHostChange(pathInfo.serviceName, hostDetail);
        }
        String parentPath = Utils.getEphemeralServicePath(pathInfo.serviceName, pathInfo.group);
        client.watchChildren(parentPath);
    }

    private boolean shouldNotify(PathInfo pathInfo) throws Exception {
        String currentGroup = configManager.getGroup();
        currentGroup = Utils.normalizeGroup(currentGroup);
        if (currentGroup.equals(pathInfo.group))
            return true;
        if (StringUtils.isEmpty(currentGroup) && !StringUtils.isEmpty(pathInfo.group))
            return false;
        if (!StringUtils.isEmpty(currentGroup) && StringUtils.isEmpty(pathInfo.group)) {
            String servicePath = Utils.getServicePath(pathInfo.serviceName, currentGroup);
            return client.exists(servicePath);
        }
        return false;
    }

    private void weightChanged(PathInfo pathInfo) throws RegistryException {
        try {
            String newValue = client.get(pathInfo.path);
            logger.info("Service weight changed, path " + pathInfo.path + " value " + newValue);
            int weight = newValue == null ? 0 : Integer.parseInt(newValue);
            serviceChangeListener.onHostWeightChange(pathInfo.server, weight);
        } catch (Exception e) {
            throw new RegistryException(e);
        }
    }

    public PathInfo parsePath(String path) {
        if (path == null)
            return null;

        PathInfo pathInfo = null;
        if (path.startsWith(Constants.SERVICE_PATH)) {
            pathInfo = new PathInfo(path);
            pathInfo.type = ADDRESS;
            pathInfo.serviceName = path.substring(Constants.SERVICE_PATH.length() + 1);
            int idx = pathInfo.serviceName.indexOf(Constants.PATH_SEPARATOR);
            if (idx != -1) {
                pathInfo.group = pathInfo.serviceName.substring(idx + 1);
                pathInfo.serviceName = pathInfo.serviceName.substring(0, idx);
            }
            pathInfo.serviceName = Utils.unescapeServiceName(pathInfo.serviceName);
            pathInfo.group = Utils.normalizeGroup(pathInfo.group);
        } else if (path.startsWith(Constants.WEIGHT_PATH)) {
            pathInfo = new PathInfo(path);
            pathInfo.type = WEIGHT;
            pathInfo.server = path.substring(Constants.WEIGHT_PATH.length() + 1);
        } else if(path.startsWith(Constants.EPHEMERAL_SERVICE_PATH)) {
            pathInfo = new PathInfo(path);
            pathInfo.type = EPHEMERAL_ADDRESS;
            pathInfo.serviceName = path.substring(Constants.EPHEMERAL_SERVICE_PATH.length() + 1);
            int idx = pathInfo.serviceName.indexOf('@');
            if(idx != -1) {
                pathInfo.group = pathInfo.serviceName.substring(idx + 1);
                pathInfo.serviceName = pathInfo.serviceName.substring(0, idx);
            }
            pathInfo.serviceName = Utils.unescapeServiceName(pathInfo.serviceName);
            pathInfo.group = Utils.normalizeGroup(pathInfo.group);
        }
        return pathInfo;
    }

    class PathInfo {
        String path;
        String serviceName;
        String group;
        String server;
        int type;

        PathInfo(String path) {
            this.path = path;
        }
    }
    
}
