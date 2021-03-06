/*
 * Licensed to the Apache Software Foundation (ASF) under one 
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY 
 * KIND, either express or implied.  See the License for the 
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.stratos.cloud.controller.services.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.stratos.cloud.controller.config.CloudControllerConfig;
import org.apache.stratos.cloud.controller.context.CloudControllerContext;
import org.apache.stratos.cloud.controller.domain.*;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesCluster;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesClusterContext;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesHost;
import org.apache.stratos.cloud.controller.domain.kubernetes.KubernetesMaster;
import org.apache.stratos.cloud.controller.exception.*;
import org.apache.stratos.cloud.controller.iaases.Iaas;
import org.apache.stratos.cloud.controller.messaging.topology.TopologyBuilder;
import org.apache.stratos.cloud.controller.messaging.topology.TopologyHolder;
import org.apache.stratos.cloud.controller.services.CloudControllerService;
import org.apache.stratos.cloud.controller.util.CloudControllerConstants;
import org.apache.stratos.cloud.controller.util.CloudControllerUtil;
import org.apache.stratos.common.Property;
import org.apache.stratos.common.domain.LoadBalancingIPType;
import org.apache.stratos.common.threading.StratosThreadPool;
import org.apache.stratos.messaging.domain.topology.*;
import org.wso2.carbon.registry.core.exceptions.RegistryException;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;

/**
 * Cloud Controller Service is responsible for starting up new server instances,
 * terminating already started instances, providing pending instance count etc.
 */
public class CloudControllerServiceImpl implements CloudControllerService {

    private static final Log log = LogFactory.getLog(CloudControllerServiceImpl.class);

    private static final String PERSISTENCE_MAPPING = "PERSISTENCE_MAPPING";
    public static final String PAYLOAD_PARAMETER = "payload_parameter.";
    public static final String KUBERNETES_PROVIDER = "kubernetes";
    public static final String KUBERNETES_CLUSTER = "cluster";

    private CloudControllerContext cloudControllerContext = CloudControllerContext.getInstance();
    private ExecutorService executorService;

    public CloudControllerServiceImpl() {
        executorService = StratosThreadPool.getExecutorService("cloud.controller.instance.manager.thread.pool", 50);

    }

    public boolean addCartridge(Cartridge cartridgeConfig)
            throws InvalidCartridgeDefinitionException, InvalidIaasProviderException, CartridgeAlreadyExistsException {

        handleNullObject(cartridgeConfig, "Cartridge definition is null");

        if (log.isInfoEnabled()) {
            log.info("Adding cartridge: [cartridge-type] " + cartridgeConfig.getType());
        }
        if (log.isDebugEnabled()) {
            log.debug("Cartridge definition: " + cartridgeConfig.toString());
        }

        try {
            CloudControllerUtil.extractIaaSProvidersFromCartridge(cartridgeConfig);
        } catch (Exception e) {
            String message = "Invalid cartridge definition: [cartridge-type] " + cartridgeConfig.getType();
            log.error(message, e);
            throw new InvalidCartridgeDefinitionException(message, e);
        }

        String cartridgeType = cartridgeConfig.getType();
        if (cloudControllerContext.getCartridge(cartridgeType) != null) {
            String message = "Cartridge already exists: [cartridge-type] " + cartridgeType;
            log.error(message);
            throw new CartridgeAlreadyExistsException(message);
        }

        try {
            // Add cartridge to the cloud controller context and persist
            CloudControllerContext.getInstance().addCartridge(cartridgeConfig);
            CloudControllerContext.getInstance().persist();

            List<Cartridge> cartridgeList = new ArrayList<>();
            cartridgeList.add(cartridgeConfig);
            TopologyBuilder.handleServiceCreated(cartridgeList);
        } catch (RegistryException e) {
            log.error("Could not persist data in registry data store", e);
            return false;
        }

        if (log.isInfoEnabled()) {
            log.info("Successfully added cartridge: [cartridge-type] " + cartridgeType);
        }
        return true;
    }

    @Override
    public boolean updateCartridge(Cartridge cartridge)
            throws InvalidCartridgeDefinitionException, InvalidIaasProviderException,
                   CartridgeDefinitionNotExistsException {

        handleNullObject(cartridge, "Cartridge definition is null");

        if (log.isInfoEnabled()) {
            log.info("Updating cartridge: [cartridge-type] " + cartridge.getType());
        }
        if (log.isDebugEnabled()) {
            log.debug("Cartridge definition: " + cartridge.toString());
        }

        try {
            CloudControllerUtil.extractIaaSProvidersFromCartridge(cartridge);
        } catch (Exception e) {
            String msg = "Invalid cartridge definition: [cartridge-type] " + cartridge.getType();
            log.error(msg, e);
            throw new InvalidCartridgeDefinitionException(msg, e);
        }

        String cartridgeType = cartridge.getType();
        if (cloudControllerContext.getCartridge(cartridgeType) != null) {
            Cartridge cartridgeToBeRemoved = cloudControllerContext.getCartridge(cartridgeType);
            try {
                removeCartridgeFromCC(cartridgeToBeRemoved.getType());
            } catch (InvalidCartridgeTypeException ignore) {
            }
            copyIaasProviders(cartridge, cartridgeToBeRemoved);
        } else {
            throw new CartridgeDefinitionNotExistsException("This cartridge definition not exists");
        }
        try {
            // Add cartridge to the cloud controller context and persist
            CloudControllerContext.getInstance().addCartridge(cartridge);
            CloudControllerContext.getInstance().persist();
            // transaction ends

            if (log.isInfoEnabled()) {
                log.info("Successfully updated cartridge: [cartridge-type] " + cartridgeType);
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to update cartridge [cartridge-type] " + cartridgeType, e);
            return false;
        }
    }

    private void copyIaasProviders(Cartridge destCartridge, Cartridge sourceCartridge) {

        List<IaasProvider> newIaasProviders = CloudControllerContext.getInstance()
                .getIaasProviders(destCartridge.getType());

        Map<String, IaasProvider> iaasProviderMap = CloudControllerContext.getInstance()
                .getPartitionToIaasProvider(sourceCartridge.getType());

        if (iaasProviderMap != null) {
            for (Entry<String, IaasProvider> entry : iaasProviderMap.entrySet()) {
                if (entry == null) {
                    continue;
                }
                String partitionId = entry.getKey();
                IaasProvider iaasProvider = entry.getValue();
                if (newIaasProviders.contains(iaasProvider)) {
                    if (log.isDebugEnabled()) {
                        log.debug("Copying partition from the cartridge that is undeployed, to the new cartridge: "
                                + "[partition-id] " + partitionId + " [cartridge-type] " + destCartridge.getType());
                    }
                    CloudControllerContext.getInstance().addIaasProvider(destCartridge.getType(), partitionId,
                            newIaasProviders.get(newIaasProviders.indexOf(iaasProvider)));
                }
            }
        }

    }

    public boolean removeCartridge(String cartridgeType) throws InvalidCartridgeTypeException {
        //Removing the cartridge from CC
        Cartridge cartridge = removeCartridgeFromCC(cartridgeType);
        //removing the cartridge from Topology
        // sends the service removed event
        List<Cartridge> cartridgeList = new ArrayList<>();
        cartridgeList.add(cartridge);
        try {
            TopologyBuilder.handleServiceRemoved(cartridgeList);
        } catch (RegistryException e) {
            log.error("Could not persist data in registry data store", e);
            return false;
        }

        if (log.isInfoEnabled()) {
            log.info("Successfully removed cartridge: [cartridge-type] " + cartridgeType);
        }
        return true;
    }

    private Cartridge removeCartridgeFromCC(String cartridgeType) throws InvalidCartridgeTypeException {
        Cartridge cartridge;
        if ((cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType)) != null) {
            if (CloudControllerContext.getInstance().getCartridges().remove(cartridge)) {
                // invalidate partition validation cache
                CloudControllerContext.getInstance().removeFromCartridgeTypeToPartitionIds(cartridgeType);

                if (log.isDebugEnabled()) {
                    log.debug("Partition cache invalidated for cartridge " + cartridgeType);
                }

                try {
                    CloudControllerContext.getInstance().persist();
                } catch (RegistryException e) {
                    log.error("Could not remove cartridge " + cartridgeType, e);
                    return null;
                }
                if (log.isInfoEnabled()) {
                    log.info("Successfully removed cartridge: [cartridge-type] " + cartridgeType);
                }
                return cartridge;
            }
        }
        String msg = "Cartridge not found: [cartridge-type] " + cartridgeType;
        log.error(msg);
        throw new InvalidCartridgeTypeException(msg);
    }

    public boolean addServiceGroup(ServiceGroup servicegroup) throws InvalidServiceGroupException {

        if (servicegroup == null) {
            String msg = "Invalid ServiceGroup Definition: Definition is null.";
            log.error(msg);
            throw new IllegalArgumentException(msg);

        }
        CloudControllerContext.getInstance().addServiceGroup(servicegroup);
        try {
            CloudControllerContext.getInstance().persist();
        } catch (RegistryException e) {
            log.error("Could not add service group: [service-group] " + servicegroup, e);
            return false;
        }
        return true;
    }

    public boolean removeServiceGroup(String name) throws InvalidServiceGroupException {
        if (log.isDebugEnabled()) {
            log.debug("CloudControllerServiceImpl:removeServiceGroup: " + name);
        }
        ServiceGroup serviceGroup;
        serviceGroup = CloudControllerContext.getInstance().getServiceGroup(name);
        if (serviceGroup != null) {
            if (CloudControllerContext.getInstance().getServiceGroups().remove(serviceGroup)) {
                try {
                    CloudControllerContext.getInstance().persist();
                } catch (RegistryException e) {
                    log.error("Could not remove service group [service-group] " + name);
                    return false;
                }
                if (log.isInfoEnabled()) {
                    log.info("Successfully removed the cartridge group: [group-name] " + serviceGroup);
                }
                return true;
            }
        }
        String msg = "Cartridge group not found: [group-name] " + name;
        log.error(msg);
        throw new InvalidServiceGroupException(msg);
    }

    @Override
    public ServiceGroup getServiceGroup(String name) throws InvalidServiceGroupException {

        if (log.isDebugEnabled()) {
            log.debug("getServiceGroupDefinition:" + name);
        }

        ServiceGroup serviceGroup = CloudControllerContext.getInstance().getServiceGroup(name);

        if (serviceGroup == null) {
            String message = "Cartridge group not found: [group-name] " + name;
            if (log.isDebugEnabled()) {
                log.debug(message);
            }
            throw new InvalidServiceGroupException(message);
        }

        return serviceGroup;
    }

    public String[] getServiceGroupSubGroups(String name) throws InvalidServiceGroupException {
        ServiceGroup serviceGroup = this.getServiceGroup(name);
        if (serviceGroup == null) {
            throw new InvalidServiceGroupException("Invalid service group: [group-name] " + name);
        }

        return serviceGroup.getSubGroups();
    }

    /**
     *
     */
    public String[] getServiceGroupCartridges(String name) throws InvalidServiceGroupException {
        ServiceGroup serviceGroup = this.getServiceGroup(name);
        if (serviceGroup == null) {
            throw new InvalidServiceGroupException("Invalid service group: [group-name] " + name);
        }
        return serviceGroup.getCartridges();
    }

    public Dependencies getServiceGroupDependencies(String name) throws InvalidServiceGroupException {
        ServiceGroup serviceGroup = this.getServiceGroup(name);
        if (serviceGroup == null) {
            throw new InvalidServiceGroupException("Invalid service group: [group-name] " + name);
        }
        return serviceGroup.getDependencies();
    }

    @Override
    public MemberContext[] startInstances(InstanceContext[] instanceContexts)
            throws CartridgeNotFoundException, InvalidIaasProviderException, CloudControllerException {

        handleNullObject(instanceContexts, "Instance start-up failed, member contexts is null");

        List<MemberContext> memberContextList = new ArrayList<>();
        for (InstanceContext instanceContext : instanceContexts) {
            if (instanceContext != null) {
                MemberContext memberContext = startInstance(instanceContext);
                memberContextList.add(memberContext);
            }
        }
        return memberContextList.toArray(new MemberContext[memberContextList.size()]);
    }

    public MemberContext startInstance(InstanceContext instanceContext)
            throws CartridgeNotFoundException, InvalidIaasProviderException, CloudControllerException {

        try {
            // Validate instance context
            handleNullObject(instanceContext, "Could not start instance, instance context is null");
            if (log.isDebugEnabled()) {
                log.debug("Starting up instance: " + instanceContext);
            }

            // Validate partition
            Partition partition = instanceContext.getPartition();
            handleNullObject(partition, "Could not start instance, partition is null");

            // Validate cluster
            String partitionId = partition.getId();
            String clusterId = instanceContext.getClusterId();
            ClusterContext clusterContext = CloudControllerContext.getInstance().getClusterContext(clusterId);
            handleNullObject(clusterContext,
                    "Could not start instance, cluster context not found: [cluster-id] " + clusterId);

            // Validate cartridge
            String cartridgeType = clusterContext.getCartridgeType();
            Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType);
            if (cartridge == null) {
                String msg = "Could not startup instance, cartridge not found: [cartridge-type] " + cartridgeType;
                log.error(msg);
                throw new CartridgeNotFoundException(msg);
            }

            // Validate iaas provider
            IaasProvider iaasProvider = CloudControllerContext.getInstance()
                    .getIaasProviderOfPartition(cartridge.getType(), partitionId);
            if (iaasProvider == null) {
                String msg = String.format("Could not start instance, " +
                                "IaaS provider not found in cartridge %s for partition %s, " +
                                "partitions found: %s ", cartridgeType, partitionId,
                        CloudControllerContext.getInstance().getPartitionToIaasProvider(cartridge.getType()).keySet()
                                .toString());
                log.error(msg);
                throw new InvalidIaasProviderException(msg);
            }

            // Generate member ID
            String memberId = generateMemberId(clusterId);

            // Create member context
            String applicationId = clusterContext.getApplicationId();

            // if the IaaS Provider type is 'ec2', add region and zone information to the Member via
            // properties of Instance Context -> properties of Member Context
            if (CloudControllerConstants.IAAS_TYPE_EC2.equalsIgnoreCase(iaasProvider.getType())) {
                instanceContext.getProperties().addProperty(
                        new Property(CloudControllerConstants.INSTANCE_CTXT_EC2_REGION,
                                instanceContext.getPartition().getProperties()
                                        .getProperty(CloudControllerConstants.REGION_ELEMENT).getValue()));
                instanceContext.getProperties().addProperty(
                        new Property(CloudControllerConstants.INSTANCE_CTXT_EC2_AVAILABILITY_ZONE,
                                instanceContext.getPartition().getProperties()
                                        .getProperty(CloudControllerConstants.ZONE_ELEMENT).getValue()));
                if (log.isDebugEnabled()) {
                    log.debug("ec2Region in InstanceContext: " + instanceContext.getProperties()
                            .getProperty(CloudControllerConstants.INSTANCE_CTXT_EC2_REGION));
                    log.debug("ec2AvailabilityZone in InstanceContext: " + instanceContext.getProperties()
                            .getProperty(CloudControllerConstants.INSTANCE_CTXT_EC2_AVAILABILITY_ZONE));
                }
            }

            MemberContext memberContext = createMemberContext(applicationId, cartridgeType, memberId,
                    CloudControllerUtil.getLoadBalancingIPTypeEnumFromString(cartridge.getLoadBalancingIPType()),
                    instanceContext);

            // Prepare payload
            StringBuilder payload = new StringBuilder(clusterContext.getPayload());
            addToPayload(payload, "MEMBER_ID", memberId);
            addToPayload(payload, "INSTANCE_ID", memberContext.getInstanceId());
            addToPayload(payload, "CLUSTER_INSTANCE_ID", memberContext.getClusterInstanceId());
            addToPayload(payload, "LB_CLUSTER_ID", memberContext.getLbClusterId());
            addToPayload(payload, "NETWORK_PARTITION_ID", memberContext.getNetworkPartitionId());
            addToPayload(payload, "PARTITION_ID", partitionId);
            addToPayload(payload, "INTERNAL", "false");

            if (memberContext.getProperties() != null) {
                org.apache.stratos.common.Properties properties = memberContext.getProperties();
                for (Property prop : properties.getProperties()) {
                    addToPayload(payload, prop.getName(), String.valueOf(prop.getValue()));
                }
            }

            NetworkPartition networkPartition = CloudControllerContext.getInstance()
                    .getNetworkPartition(memberContext.getNetworkPartitionId());

            if (networkPartition.getProperties() != null) {
                if (networkPartition.getProperties().getProperties() != null) {
                    for (Property property : networkPartition.getProperties().getProperties()) {
                        // check if a property is related to the payload. Currently
                        // this is done by checking if the
                        // property name starts with 'payload_parameter.' suffix. If
                        // so the payload param name will
                        // be taken as the substring from the index of '.' to the
                        // end of the property name.
                        if (property.getName().startsWith(PAYLOAD_PARAMETER)) {
                            String propertyName = property.getName();
                            String payloadParamName = propertyName.substring(propertyName.indexOf(".") + 1);
                            if (payload.toString().contains(payloadParamName)) {
                                replaceInPayload(payloadParamName, payload, payloadParamName, property.getValue());
                            } else {
                                addToPayload(payload, payloadParamName, property.getValue());
                            }
                        }
                    }
                }
            }

            Iaas iaas = iaasProvider.getIaas();
            if (clusterContext.isVolumeRequired()) {
                addToPayload(payload, PERSISTENCE_MAPPING, getPersistencePayload(clusterContext, iaas).toString());
            }

            if (log.isDebugEnabled()) {
                log.debug("Payload: " + payload.toString());
            }

            if (clusterContext.isVolumeRequired()) {

                Volume[] volumes = clusterContext.getVolumes();
                if (volumes != null) {
                    for (int i = 0; i < volumes.length; i++) {

                        if (volumes[i].getId() == null) {
                            // Create a new volume
                            volumes[i] = createVolumeAndSetInClusterContext(volumes[i], iaasProvider);
                        }
                    }
                }
                clusterContext.setVolumes(volumes);
            }

            // Handle member created event
            TopologyBuilder.handleMemberCreatedEvent(memberContext);

            // Persist member context
            CloudControllerContext.getInstance().addMemberContext(memberContext);
            CloudControllerContext.getInstance().persist();

            // Start instance in a new thread
            if (log.isDebugEnabled()) {
                log.debug(String.format("Starting instance creator thread: [cluster] %s [cluster-instance] %s "
                                + "[member] %s [application-id] %s", instanceContext.getClusterId(),
                        instanceContext.getClusterInstanceId(), memberId, applicationId));
            }
            executorService.execute(new InstanceCreator(memberContext, iaasProvider, payload.toString().getBytes()));

            return memberContext;
        } catch (Exception e) {
            String msg = String.format("Could not start instance: [cluster] %s [cluster-instance] %s",
                    instanceContext.getClusterId(), instanceContext.getClusterInstanceId());
            log.error(msg, e);
            throw new CloudControllerException(msg, e);
        }
    }

    private MemberContext createMemberContext(String applicationId, String cartridgeType, String memberId,
            LoadBalancingIPType loadBalancingIPType, InstanceContext instanceContext) {
        MemberContext memberContext = new MemberContext(applicationId, cartridgeType, instanceContext.getClusterId(),
                memberId);

        memberContext.setClusterInstanceId(instanceContext.getClusterInstanceId());
        memberContext.setNetworkPartitionId(instanceContext.getNetworkPartitionId());
        memberContext.setPartition(cloudControllerContext.getNetworkPartition(instanceContext.getNetworkPartitionId()).
                getPartition(instanceContext.getPartition().getId()));
        memberContext.setInitTime(instanceContext.getInitTime());
        memberContext.setProperties(instanceContext.getProperties());
        memberContext.setLoadBalancingIPType(loadBalancingIPType);
        memberContext.setInitTime(System.currentTimeMillis());
        memberContext.setObsoleteExpiryTime(instanceContext.getObsoleteExpiryTime());

        return memberContext;
    }

    private Volume createVolumeAndSetInClusterContext(Volume volume, IaasProvider iaasProvider) {
        // iaas cannot be null at this state #startInstance method
        Iaas iaas = iaasProvider.getIaas();
        int sizeGB = volume.getSize();
        String snapshotId = volume.getSnapshotId();
        if (StringUtils.isNotEmpty(volume.getVolumeId())) {
            // volumeID is specified, so not creating additional volumes
            if (log.isDebugEnabled()) {
                log.debug("Volume creation is skipping since a volume ID is specified. [Volume ID] " + volume
                        .getVolumeId());
            }
            volume.setId(volume.getVolumeId());
        } else {
            String volumeId = iaas.createVolume(sizeGB, snapshotId);
            volume.setId(volumeId);
        }

        volume.setIaasType(iaasProvider.getType());

        return volume;
    }

    private StringBuilder getPersistencePayload(ClusterContext ctx, Iaas iaas) {
        StringBuilder persistencePayload = new StringBuilder();
        if (isPersistenceMappingAvailable(ctx)) {
            for (Volume volume : ctx.getVolumes()) {
                if (log.isDebugEnabled()) {
                    log.debug("Adding persistence mapping " + volume.toString());
                }
                if (persistencePayload.length() != 0) {
                    persistencePayload.append("|");
                }

                persistencePayload.append(iaas.getIaasDevice(volume.getDevice()));
                persistencePayload.append("|");
                persistencePayload.append(volume.getId());
                persistencePayload.append("|");
                persistencePayload.append(volume.getMappingPath());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Persistence payload: " + persistencePayload.toString());
        }
        return persistencePayload;
    }

    private boolean isPersistenceMappingAvailable(ClusterContext ctx) {
        return ctx.getVolumes() != null && ctx.isVolumeRequired();
    }

    private void addToPayload(StringBuilder payload, String name, String value) {
        payload.append(",");
        payload.append(name).append("=").append(value);
    }

    private void replaceInPayload(String payloadParamName, StringBuilder payload, String name, String value) {

        payload.replace(payload.indexOf(payloadParamName), payload.indexOf(",", payload.indexOf(payloadParamName)),
                "," + name + "=" + value);
    }

    private String generateMemberId(String clusterId) {
        UUID memberId = UUID.randomUUID();
        return clusterId + memberId.toString();
    }

    public boolean terminateInstanceForcefully(String memberId) {

        log.info(String.format("Starting to forcefully terminate the member [member-id] %s", memberId));
        boolean memberTerminated = true;
        try {
            this.terminateInstance(memberId);
        } catch (InvalidMemberException | InvalidCartridgeTypeException | CloudControllerException e) {
            memberTerminated = false;
        }

        if (memberTerminated) {
            log.info(String.format("Member terminated [member-id] %s ", memberId));
        } else {
            log.warn(String.format("Stratos could not terminate the member [member-id] %s. This may due to a issue "
                    + "in the underlying IaaS, Please terminate the member manually if it is available", memberId));
            MemberContext memberContext = CloudControllerContext.getInstance().getMemberContextOfMemberId(memberId);
            try {
                CloudControllerServiceUtil.executeMemberTerminationPostProcess(memberContext);
            } catch (RegistryException e) {
                log.error(String.format(
                        "Could not persist data in registry data store while forcefully terminating member "
                                + "[member-id] %s", memberId), e);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean terminateInstance(String memberId)
            throws InvalidMemberException, InvalidCartridgeTypeException, CloudControllerException {

        try {
            handleNullObject(memberId, "Could not terminate instance, member id is null.");

            MemberContext memberContext = CloudControllerContext.getInstance().getMemberContextOfMemberId(memberId);
            if (memberContext == null) {
                String msg = "Could not terminate instance, member context not found: [member-id] " + memberId;
                if (log.isErrorEnabled()) {
                    log.error(msg);
                }
                throw new InvalidMemberException(msg);
            }

            if (StringUtils.isBlank(memberContext.getInstanceId())) {
                if (log.isErrorEnabled()) {
                    log.error(String.format("Could not terminate instance, instance id is blank: [member-id] %s "
                            + ", removing member from topology...", memberContext.getMemberId()));
                }
                CloudControllerServiceUtil.executeMemberTerminationPostProcess(memberContext);
                String msg = "Could not terminate instance, member instance id is empty: " + memberContext.toString();
                throw new InvalidMemberException(msg);
            }

            try {
                // check if status == active, if true, then this is a termination on member faulty
                TopologyHolder.acquireWriteLock();
                Topology topology = TopologyHolder.getTopology();
                org.apache.stratos.messaging.domain.topology.Service service = topology
                        .getService(memberContext.getCartridgeType());

                if (service != null) {
                    Cluster cluster = service.getCluster(memberContext.getClusterId());
                    if (cluster != null) {
                        Member member = cluster.getMember(memberId);
                        if (member != null) {

                            // check if ready to shutdown member is expired and send
                            // member terminated if it is.
                            if (isMemberExpired(member, memberContext.getObsoleteInitTime(),
                                    memberContext.getObsoleteExpiryTime())) {
                                if (log.isInfoEnabled()) {
                                    log.info(String.format(
                                            "Member pending termination in ReadyToShutdown state exceeded expiry time. "
                                                    + "This member has to be manually deleted: %s",
                                            memberContext.getMemberId()));
                                }

                                CloudControllerServiceUtil.executeMemberTerminationPostProcess(memberContext);
                                return false;
                            }
                        }
                    }
                }
                executorService.execute(new InstanceTerminator(memberContext));
            } finally {
                TopologyHolder.releaseWriteLock();
            }
        } catch (Exception e) {
            String message = "Could not terminate instance: [member-id] " + memberId;
            throw new CloudControllerException(message, e);
        }
        return true;
    }

    /**
     * Check if a member has been in the ReadyToShutdown status for a specified expiry time
     *
     * @param member     Member to be checked for expiration timeout
     * @param initTime   Member started time
     * @param expiryTime Member expiry time
     * @return Returns true if member has been in ReadyToShutdown status for specified time period, otherwise false
     */
    private boolean isMemberExpired(Member member, long initTime, long expiryTime) {
        if (member.getStatus() == MemberStatus.ReadyToShutDown) {
            if (initTime == 0) {
                // obsolete init time hasn't been set, i.e. not a member detected faulty.
                // this is a graceful shutdown
                return false;
            }

            // member detected faulty, calculate ready to shutdown waiting period
            long timeInReadyToShutdownStatus = System.currentTimeMillis() - initTime;
            return timeInReadyToShutdownStatus >= expiryTime;
        }

        return false;
    }

    @Override
    public boolean terminateInstances(String clusterId) throws InvalidClusterException {

        log.info("Starting to terminate all instances of cluster : " + clusterId);

        handleNullObject(clusterId, "Instance termination failed. Cluster id is null.");

        List<MemberContext> memberContexts = CloudControllerContext.getInstance()
                .getMemberContextsOfClusterId(clusterId);
        if (memberContexts == null) {
            String msg = "Instance termination failed. No members found for cluster id: " + clusterId;
            log.warn(msg);
            return false;
        }

        for (MemberContext memberContext : memberContexts) {
            executorService.execute(new InstanceTerminator(memberContext));
        }
        return true;
    }

    @Override
    public boolean registerService(Registrant registrant) throws CartridgeNotFoundException {

        String cartridgeType = registrant.getCartridgeType();
        handleNullObject(cartridgeType, "Service registration failed, cartridge Type is null.");

        String clusterId = registrant.getClusterId();
        handleNullObject(clusterId, "Service registration failed, cluster id is null.");

        String payload = registrant.getPayload();
        handleNullObject(payload, "Service registration failed, payload is null.");

        String hostName = registrant.getHostName();
        handleNullObject(hostName, "Service registration failed, hostname is null.");

        if ((CloudControllerContext.getInstance().getCartridge(cartridgeType)) == null) {
            String msg = "Registration of cluster: " + clusterId +
                    " failed, cartridge not found: [cartridge-type] " + cartridgeType;
            log.error(msg);
            throw new CartridgeNotFoundException(msg);
        }
        try {
            CloudControllerContext.getInstance().persist();
        } catch (RegistryException e) {
            log.error("Could not register service for cartridge [cartridge-type] " + cartridgeType, e);
            return false;
        }
        log.info("Successfully registered service: " + registrant);
        return true;
    }

    @Override
    public String[] getCartridges() {
        // get the list of cartridges registered
        Collection<Cartridge> cartridges = CloudControllerContext.getInstance().getCartridges();

        if (cartridges == null) {
            log.info("No registered Cartridge found.");
            return new String[0];
        }

        String[] cartridgeTypes = new String[cartridges.size()];
        int i = 0;

        if (log.isDebugEnabled()) {
            log.debug("Registered Cartridges : \n");
        }
        for (Cartridge cartridge : cartridges) {
            if (log.isDebugEnabled()) {
                log.debug(cartridge);
            }
            cartridgeTypes[i] = cartridge.getType();
            i++;
        }

        return cartridgeTypes;
    }

    @Override
    public Cartridge getCartridge(String cartridgeType) throws CartridgeNotFoundException {
        Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType);
        if (cartridge != null) {
            return cartridge;
        }

        String msg = "Could not find cartridge: [cartridge-type] " + cartridgeType;
        throw new CartridgeNotFoundException(msg);
    }

    @Override
    public boolean unregisterService(String clusterId) throws UnregisteredClusterException {
        final String clusterId_ = clusterId;

        ClusterContext ctxt = CloudControllerContext.getInstance().getClusterContext(clusterId_);
        handleNullObject(ctxt, "Service unregistration failed. Invalid cluster id: " + clusterId);

        final String cartridgeType = ctxt.getCartridgeType();
        Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType);

        if (cartridge == null) {
            String msg = String
                    .format("Service unregistration failed. No matching cartridge found: [cartridge-type] %s "
                            + "[application-id] %s", cartridgeType, ctxt.getApplicationId());
            log.error(msg);
            throw new UnregisteredClusterException(msg);
        }

        Runnable terminateInTimeout = new Runnable() {
            @Override
            public void run() {
                ClusterContext ctxt = CloudControllerContext.getInstance().getClusterContext(clusterId_);
                if (ctxt == null) {
                    String msg = String
                            .format("Service unregistration failed. Cluster not found: [cluster-id] %s ", clusterId_);
                    log.error(msg);
                    return;
                }
                Collection<Member> members = TopologyHolder.getTopology().
                        getService(ctxt.getCartridgeType()).getCluster(clusterId_).getMembers();
                //finding the responding members from the existing members in the topology.
                int sizeOfRespondingMembers = 0;
                for (Member member : members) {
                    if (member.getStatus().getCode() >= MemberStatus.Active.getCode()) {
                        sizeOfRespondingMembers++;
                    }
                }

                long endTime = System.currentTimeMillis() + ctxt.getTimeoutInMillis() * sizeOfRespondingMembers;
                while (System.currentTimeMillis() < endTime) {
                    CloudControllerUtil.sleep(1000);

                }

                // if there are still alive members
                if (members.size() > 0) {
                    //forcefully terminate them
                    for (Member member : members) {

                        try {
                            terminateInstance(member.getMemberId());
                        } catch (Exception e) {
                            // we are not gonna stop the execution due to errors.
                            log.warn((String.format(
                                    "Instance termination failed of member [member-id] %s " + "[application-id] %s",
                                    member.getMemberId(), ctxt.getApplicationId())), e);

                        }
                    }
                }
            }
        };
        Runnable unregister = new Runnable() {
            public void run() {
                Lock lock = null;
                try {
                    lock = CloudControllerContext.getInstance().acquireClusterContextWriteLock();
                    ClusterContext ctxt = CloudControllerContext.getInstance().getClusterContext(clusterId_);
                    if (ctxt == null) {
                        String msg = String.format("Service unregistration failed. Cluster not found: [cluster-id] %s ",
                                clusterId_);
                        log.error(msg);
                        return;
                    }
                    Collection<Member> members = TopologyHolder.getTopology().
                            getService(ctxt.getCartridgeType()).getCluster(clusterId_).getMembers();

                    while (members.size() > 0) {
                        //waiting until all the members got removed from the Topology/ timed out
                        CloudControllerUtil.sleep(1000);
                    }

                    log.info(String.format("Unregistering service cluster: [cluster-id] %s [application-id] %s",
                            clusterId_, ctxt.getApplicationId()));
                    deleteVolumes(ctxt);
                    TopologyBuilder.handleClusterRemoved(ctxt);
                    CloudControllerContext.getInstance().removeClusterContext(clusterId_);
                    CloudControllerContext.getInstance().removeMemberContextsOfCluster(clusterId_);
                    CloudControllerContext.getInstance().persist();
                } catch (RegistryException e) {
                    log.error("Could not persist data in registry data store", e);
                } finally {
                    if (lock != null) {
                        CloudControllerContext.getInstance().releaseWriteLock(lock);
                    }
                }
            }

            private void deleteVolumes(ClusterContext ctxt) {
                if (ctxt.isVolumeRequired()) {
                    Lock lock = null;
                    try {
                        lock = CloudControllerContext.getInstance().acquireCartridgesWriteLock();

                        Cartridge cartridge = CloudControllerContext.getInstance()
                                .getCartridge(ctxt.getCartridgeType());
                        if (cartridge != null
                                && CloudControllerContext.getInstance().getIaasProviders(cartridge.getType()) != null
                                && ctxt.getVolumes() != null) {
                            for (Volume volume : ctxt.getVolumes()) {
                                if (volume.getId() != null) {
                                    String iaasType = volume.getIaasType();
                                    Iaas iaas = CloudControllerContext.getInstance()
                                            .getIaasProvider(cartridge.getType(), iaasType).getIaas();
                                    if (iaas != null) {
                                        try {
                                            // delete the volumes if remove on unsubscription is true.
                                            if (volume.isRemoveOntermination()) {
                                                iaas.deleteVolume(volume.getId());
                                                volume.setId(null);
                                            }
                                        } catch (Exception ignore) {
                                            if (log.isErrorEnabled()) {
                                                log.error((String.format(
                                                        "Error while deleting volume [id] %s [application-id] %s",
                                                        volume.getId(), ctxt.getApplicationId())), ignore);
                                            }
                                        }
                                    }
                                }
                            }
                            CloudControllerContext.getInstance().updateCartridge(cartridge);
                        }
                    } finally {
                        if (lock != null) {
                            CloudControllerContext.getInstance().releaseWriteLock(lock);
                        }
                    }
                }
            }
        };
        new Thread(terminateInTimeout).start();
        new Thread(unregister).start();
        return true;
    }

    /**
     * FIXME: A validate method shouldn't persist data
     */
    @Override
    public boolean validateDeploymentPolicyNetworkPartition(String cartridgeType, String networkPartitionId)
            throws InvalidPartitionException, InvalidCartridgeTypeException {

        NetworkPartition networkPartition = CloudControllerContext.getInstance()
                .getNetworkPartition(networkPartitionId);
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireCartridgesWriteLock();

            List<String> validatedPartitions = CloudControllerContext.getInstance().getPartitionIds(cartridgeType);
            if (validatedPartitions != null) {
                // cache hit for this cartridge
                // get list of partitions
                if (log.isDebugEnabled()) {
                    log.debug("Partition validation cache hit for cartridge type: " + cartridgeType);
                }
            }

            Map<String, IaasProvider> partitionToIaasProviders = new ConcurrentHashMap<String, IaasProvider>();

            if (log.isDebugEnabled()) {
                log.debug("Deployment policy validation started for cartridge type: " + cartridgeType);
            }

            Cartridge cartridge = CloudControllerContext.getInstance().getCartridge(cartridgeType);
            if (cartridge == null) {
                String msg = "Cartridge not found: " + cartridgeType;
                log.error(msg);
                throw new InvalidCartridgeTypeException(msg);
            }

            for (Partition partition : networkPartition.getPartitions()) {
                if (validatedPartitions != null && validatedPartitions.contains(partition.getId())) {
                    // partition cache hit
                    String provider = partition.getProvider();
                    IaasProvider iaasProvider = CloudControllerContext.getInstance()
                            .getIaasProvider(cartridge.getType(), provider);
                    partitionToIaasProviders.put(partition.getId(), iaasProvider);
                    continue;
                }

                if (log.isDebugEnabled()) {
                    log.debug("Partition validation started for " + partition + " of " + cartridge);
                }

                // cache miss
                IaasProvider iaasProvider = CloudControllerContext.getInstance()
                        .getIaasProvider(cartridge.getType(), partition.getProvider());
                IaasProvider updatedIaasProvider = CloudControllerServiceUtil
                        .validatePartitionAndGetIaasProvider(partition, iaasProvider);

                try {
                    if (updatedIaasProvider != null) {
                        partitionToIaasProviders.put(partition.getId(), updatedIaasProvider);
                    }

                    // add to cache
                    CloudControllerContext.getInstance()
                            .addToCartridgeTypeToPartitionIdMap(cartridgeType, partition.getId());
                    if (log.isDebugEnabled()) {
                        log.debug("Partition " + partition.getId() + " added to the cache against " + "cartridge: " +
                                "[cartridge-type] " + cartridgeType);
                    }

                } catch (Exception e) {
                    String message = "Could not cache partitions against the cartridge: [cartridge-type] "
                            + cartridgeType;
                    log.error(message, e);
                    throw new InvalidPartitionException(message, e);
                }

            }

            // if and only if the deployment policy valid
            CloudControllerContext.getInstance().addIaasProviders(cartridgeType, partitionToIaasProviders);
            CloudControllerContext.getInstance().updateCartridge(cartridge);

            // persist data
            CloudControllerContext.getInstance().persist();

            log.info("All partitions [" + CloudControllerUtil.getPartitionIds(networkPartition.getPartitions()) + "]" +
                    " were validated successfully, against the cartridge: " + cartridgeType);

            return true;
        } catch (RegistryException e) {
            log.error("Failed to persist data in registry data store", e);
            return false;
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    @Override
    public boolean validatePartition(Partition partition) throws InvalidPartitionException {
        handleNullObject(partition, "Partition validation failed. Partition is null.");

        String provider = partition.getProvider();
        String partitionId = partition.getId();

        handleNullObject(provider, "Partition [" + partitionId + "] validation failed. Partition provider is null.");
        IaasProvider iaasProvider = CloudControllerConfig.getInstance().getIaasProvider(provider);

        return CloudControllerServiceUtil.validatePartition(partition, iaasProvider);
    }

    public ClusterContext getClusterContext(String clusterId) {
        return CloudControllerContext.getInstance().getClusterContext(clusterId);
    }

    @Override
    public boolean updateClusterStatus(String serviceName, String clusterId, String instanceId, ClusterStatus status) {
        //TODO
        return true;
    }

    private void handleNullObject(Object obj, String errorMsg) {
        if (obj == null) {
            log.error(errorMsg);
            throw new CloudControllerException(errorMsg);
        }
    }

    @Override
    public boolean createApplicationClusters(String appId, ApplicationClusterContext[] appClustersContexts)
            throws ApplicationClusterRegistrationException {
        if (appClustersContexts == null || appClustersContexts.length == 0) {
            String errorMsg = "No application cluster information found, unable to create clusters: " +
                    "[application-id] " + appId;
            log.error(errorMsg);
            throw new ApplicationClusterRegistrationException(errorMsg);
        }

        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireClusterContextWriteLock();
            // Create a cluster context & cluster object for each cluster in the application

            List<Cluster> clusters = new ArrayList<>();
            for (ApplicationClusterContext appClusterCtxt : appClustersContexts) {
                ClusterContext clusterContext = new ClusterContext(appId, appClusterCtxt.getCartridgeType(),
                        appClusterCtxt.getClusterId(), appClusterCtxt.getTextPayload(), appClusterCtxt.getHostName(),
                        appClusterCtxt.isLbCluster(), appClusterCtxt.getProperties());

                if (appClusterCtxt.isVolumeRequired()) {
                    clusterContext.setVolumeRequired(true);
                    clusterContext.setVolumes(appClusterCtxt.getVolumes());
                }
                CloudControllerContext.getInstance().addClusterContext(clusterContext);

                // Create cluster object
                List<String> loadBalancerIps = findLoadBalancerIps(appId, appClusterCtxt);
                Cluster cluster = new Cluster(appClusterCtxt.getCartridgeType(), appClusterCtxt.getClusterId(),
                        appClusterCtxt.getDeploymentPolicyName(), appClusterCtxt.getAutoscalePolicyName(), appId);
                cluster.setLbCluster(false);
                cluster.setTenantRange(appClusterCtxt.getTenantRange());
                cluster.setHostNames(Collections.singletonList(appClusterCtxt.getHostName()));
                cluster.setLoadBalancerIps(loadBalancerIps);

                if (appClusterCtxt.getProperties() != null) {
                    Properties properties = CloudControllerUtil.toJavaUtilProperties(appClusterCtxt.getProperties());
                    cluster.setProperties(properties);
                }
                clusters.add(cluster);
            }
            TopologyBuilder.handleApplicationClustersCreated(appId, clusters);
            CloudControllerContext.getInstance().persist();
        } catch (RegistryException e) {
            log.error("Could not persist data in registry data store", e);
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
        return true;
    }

    /**
     * Find load balancer ips from application subscribable properties or network partition properties.
     *
     * @param applicationId
     * @param applicationClusterContext
     * @return
     */
    private List<String> findLoadBalancerIps(String applicationId,
            ApplicationClusterContext applicationClusterContext) {

        Cartridge cartridge = CloudControllerContext.getInstance().
                getCartridge(applicationClusterContext.getCartridgeType());
        if (cartridge == null) {
            throw new CloudControllerException("Cartridge not found: " + applicationClusterContext.getCartridgeType());
        }

        String clusterId = applicationClusterContext.getClusterId();
        org.apache.stratos.common.Properties appClusterContextProperties = applicationClusterContext.getProperties();

        if (appClusterContextProperties != null) {
            // Find load balancer ips from application subscribable properties
            Property ipListProperty = appClusterContextProperties
                    .getProperty(CloudControllerConstants.LOAD_BALANCER_IPS);
            if (ipListProperty != null) {
                log.info(String.format("Load balancer IPs found in application: [application] %s [cluster] %s "
                        + "[load-balancer-ip-list] %s", applicationId, clusterId, ipListProperty.getValue()));
                return transformToList(ipListProperty);
            }

            // Find load balancer ips from network partition properties
            Property npListProperty = appClusterContextProperties
                    .getProperty(CloudControllerConstants.NETWORK_PARTITION_ID_LIST);
            if (npListProperty != null) {
                String npIdListStr = npListProperty.getValue();
                if (StringUtils.isNotEmpty(npIdListStr)) {
                    List<String> loadBalancerIps = new ArrayList<>();
                    String[] npIdArray = npIdListStr.split(",");
                    for (String networkPartitionId : npIdArray) {
                        NetworkPartition networkPartition = CloudControllerContext.getInstance().
                                getNetworkPartition(networkPartitionId);
                        if (networkPartition == null) {
                            throw new CloudControllerException(String.format(
                                    "Network partition not found: [application] %s " + "[network-partition] %s",
                                    applicationId, networkPartitionId));
                        }

                        org.apache.stratos.common.Properties npProperties = networkPartition.getProperties();
                        if (npProperties != null) {
                            ipListProperty = npProperties.getProperty(CloudControllerConstants.LOAD_BALANCER_IPS);
                            if (ipListProperty != null) {
                                log.info(String.format("Load balancer IPs found in network partition: "
                                                + "[application] %s [cluster] %s [load-balancer-ip-list] %s",
                                        applicationId,
                                        clusterId, ipListProperty.getValue()));
                                String[] ipArray = ipListProperty.getValue().split(",");
                                for (String ip : ipArray) {
                                    loadBalancerIps.add(ip);
                                }
                            }
                        }
                    }
                    return loadBalancerIps;
                }
            }
        }
        return null;
    }

    private List<String> transformToList(Property listProperty) {
        List<String> stringList = new ArrayList<>();
        String[] array = listProperty.getValue().split(",");
        for (String item : array) {
            stringList.add(item);
        }
        return stringList;
    }

    public boolean createClusterInstance(String serviceType, String clusterId, String alias, String instanceId,
            String partitionId, String networkPartitionId) throws ClusterInstanceCreationException {
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireClusterContextWriteLock();
            TopologyBuilder.handleClusterInstanceCreated(serviceType, clusterId, alias, instanceId, partitionId,
                    networkPartitionId);

            CloudControllerContext.getInstance().persist();
        } catch (RegistryException e) {
            log.error("Could not persist data in registry data store", e);
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
        return true;
    }

    @Override
    public KubernetesCluster[] getKubernetesClusters() {
        return CloudControllerContext.getInstance().getKubernetesClusters();
    }

    @Override
    public KubernetesCluster getKubernetesCluster(String kubernetesClusterId)
            throws NonExistingKubernetesClusterException {
        return CloudControllerContext.getInstance().getKubernetesCluster(kubernetesClusterId);
    }

    @Override
    public KubernetesMaster getMasterForKubernetesCluster(String kubernetesClusterId)
            throws NonExistingKubernetesClusterException {
        return CloudControllerContext.getInstance().getKubernetesMasterInGroup(kubernetesClusterId);
    }

    @Override
    public KubernetesHost[] getHostsForKubernetesCluster(String kubernetesClusterId)
            throws NonExistingKubernetesClusterException {
        return CloudControllerContext.getInstance().getKubernetesHostsInGroup(kubernetesClusterId);
    }

    @Override
    public boolean addKubernetesCluster(KubernetesCluster kubernetesCluster)
            throws InvalidKubernetesClusterException, KubernetesClusterAlreadyExistsException {
        if (kubernetesCluster == null) {
            throw new InvalidKubernetesClusterException("Kubernetes cluster cannot be null");
        }

        try {
            if (CloudControllerContext.getInstance().getKubernetesCluster(kubernetesCluster.getClusterId()) != null) {
                throw new KubernetesClusterAlreadyExistsException("Kubernetes cluster already exists");
            }
        } catch (NonExistingKubernetesClusterException ignore) {
        }
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireKubernetesClusterWriteLock();

            if (log.isInfoEnabled()) {
                log.info(String.format("Adding kubernetes cluster: [kubernetes-cluster-id] %s",
                        kubernetesCluster.getClusterId()));
            }
            CloudControllerUtil.validateKubernetesCluster(kubernetesCluster);

            // Add to information model
            CloudControllerContext.getInstance().addKubernetesCluster(kubernetesCluster);
            CloudControllerContext.getInstance().persist();

            if (log.isInfoEnabled()) {
                log.info(String.format("Kubernetes cluster added successfully: [kubernetes-cluster-id] %s",
                        kubernetesCluster.getClusterId()));
            }
            return true;
        } catch (Exception e) {
        	log.error("Error occurred when adding kubernetes cluster. " + e.getMessage(), e);
            throw new InvalidKubernetesClusterException(e.getMessage(), e);
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    @Override
    public boolean updateKubernetesCluster(KubernetesCluster kubernetesCluster)
            throws InvalidKubernetesClusterException {
        if (kubernetesCluster == null) {
            throw new InvalidKubernetesClusterException("Kubernetes cluster cannot be null");
        }
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireKubernetesClusterWriteLock();

            if (log.isInfoEnabled()) {
                log.info(String.format("Updating kubernetes cluster: [kubernetes-cluster-id] %s",
                        kubernetesCluster.getClusterId()));
            }
            CloudControllerUtil.validateKubernetesCluster(kubernetesCluster);

            // Updating the information model
            CloudControllerContext.getInstance().updateKubernetesCluster(kubernetesCluster);
            KubernetesClusterContext kubClusterContext = CloudControllerContext.getInstance().
            		getKubernetesClusterContext(kubernetesCluster.getClusterId());
            
            // Update necessary parameters of kubClusterContext using the updated kubCluster
            kubClusterContext.updateKubClusterContextParams(kubernetesCluster);            
            CloudControllerContext.getInstance().updateKubernetesClusterContext(kubClusterContext);
            CloudControllerContext.getInstance().persist();

            if (log.isInfoEnabled()) {
                log.info(String.format("Kubernetes cluster updated successfully: [kubernetes-cluster-id] %s",
                        kubernetesCluster.getClusterId()));
            }
            return true;
        } catch (Exception e) {
            throw new InvalidKubernetesClusterException(e.getMessage(), e);
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    @Override
    public boolean addKubernetesHost(String kubernetesClusterId, KubernetesHost kubernetesHost)
            throws InvalidKubernetesHostException, NonExistingKubernetesClusterException {
        if (kubernetesHost == null) {
            throw new InvalidKubernetesHostException("Kubernetes host cannot be null");
        }
        if (StringUtils.isEmpty(kubernetesClusterId)) {
            throw new NonExistingKubernetesClusterException("Kubernetes cluster id cannot be null");
        }

        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireKubernetesClusterWriteLock();

            if (log.isInfoEnabled()) {
                log.info(String.format(
                        "Adding kubernetes host for kubernetes cluster: [kubernetes-cluster-id] %s " + "[hostname] %s",
                        kubernetesClusterId, kubernetesHost.getHostname()));
            }
            CloudControllerUtil.validateKubernetesHost(kubernetesHost);

            KubernetesCluster kubernetesCluster = getKubernetesCluster(kubernetesClusterId);
            ArrayList<KubernetesHost> kubernetesHostArrayList;

            if (kubernetesCluster.getKubernetesHosts() == null) {
                kubernetesHostArrayList = new ArrayList<>();
            } else {
                if (CloudControllerContext.getInstance().kubernetesHostExists(kubernetesHost.getHostId())) {
                    throw new InvalidKubernetesHostException(
                            "Kubernetes host already exists: [hostname] " + kubernetesHost.getHostId());
                }
                kubernetesHostArrayList = new ArrayList<>(Arrays.asList(kubernetesCluster.getKubernetesHosts()));
            }
            kubernetesHostArrayList.add(kubernetesHost);

            // Update information model
            kubernetesCluster.setKubernetesHosts(
                    kubernetesHostArrayList.toArray(new KubernetesHost[kubernetesHostArrayList.size()]));
            CloudControllerContext.getInstance().updateKubernetesCluster(kubernetesCluster);
            CloudControllerContext.getInstance().persist();

            if (log.isInfoEnabled()) {
                log.info(
                        String.format("Kubernetes host added successfully: [id] %s", kubernetesCluster.getClusterId()));
            }

            return true;
        } catch (Exception e) {
            throw new InvalidKubernetesHostException(e.getMessage(), e);
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    @Override
    public boolean removeKubernetesCluster(String kubernetesClusterId)
            throws NonExistingKubernetesClusterException, KubernetesClusterAlreadyUsedException {
        if (StringUtils.isEmpty(kubernetesClusterId)) {
            throw new NonExistingKubernetesClusterException("Kubernetes cluster id cannot be empty");
        }
        Collection<NetworkPartition> networkPartitions = CloudControllerContext.getInstance().getNetworkPartitions();
        for (NetworkPartition networkPartition : networkPartitions) {
            if (networkPartition.getProvider().equals(KUBERNETES_PROVIDER)) {
                for (Partition partition : networkPartition.getPartitions()) {
                    if (partition.getProperties().getProperty(KUBERNETES_CLUSTER).getValue()
                            .equals(kubernetesClusterId)) {
                        throw new KubernetesClusterAlreadyUsedException(
                                "Kubernetes cluster is already used in the network partition");
                    }
                }
            }
        }

        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireKubernetesClusterWriteLock();

            if (log.isInfoEnabled()) {
                log.info("Removing Kubernetes cluster: " + kubernetesClusterId);
            }
            // Remove entry from information model
            CloudControllerContext.getInstance().removeKubernetesCluster(kubernetesClusterId);

            if (log.isInfoEnabled()) {
                log.info(String.format("Kubernetes cluster removed successfully: [id] %s", kubernetesClusterId));
            }

            CloudControllerContext.getInstance().persist();

        } catch (RegistryException e) {
            log.error("Could not remove Kubernetes cluster", e);
            return false;
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
        return true;
    }

    @Override
    public boolean removeKubernetesHost(String kubernetesHostId) throws NonExistingKubernetesHostException {
        if (kubernetesHostId == null) {
            throw new NonExistingKubernetesHostException("Kubernetes host id cannot be null");
        }

        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireKubernetesClusterWriteLock();

            if (log.isInfoEnabled()) {
                log.info("Removing Kubernetes Host: " + kubernetesHostId);
            }
            try {
                KubernetesCluster kubernetesClusterStored = CloudControllerContext.getInstance()
                        .getKubernetesClusterContainingHost(kubernetesHostId);

                // Kubernetes master cannot be removed
                if (kubernetesClusterStored.getKubernetesMaster().getHostId().equals(kubernetesHostId)) {
                    throw new NonExistingKubernetesHostException(
                            "Kubernetes master is not allowed to be removed [id] " + kubernetesHostId);
                }

                List<KubernetesHost> kubernetesHostList = new ArrayList<>();
                for (KubernetesHost kubernetesHost : kubernetesClusterStored.getKubernetesHosts()) {
                    if (!kubernetesHost.getHostId().equals(kubernetesHostId)) {
                        kubernetesHostList.add(kubernetesHost);
                    }
                }
                // member count will be equal only when host object was not found
                if (kubernetesHostList.size() == kubernetesClusterStored.getKubernetesHosts().length) {
                    throw new NonExistingKubernetesHostException(
                            "Kubernetes host not found for [id] " + kubernetesHostId);
                }
                KubernetesHost[] kubernetesHostsArray = new KubernetesHost[kubernetesHostList.size()];
                kubernetesHostList.toArray(kubernetesHostsArray);

                // Update information model
                kubernetesClusterStored.setKubernetesHosts(kubernetesHostsArray);

                if (log.isInfoEnabled()) {
                    log.info(String.format("Kubernetes host removed successfully: [id] %s", kubernetesHostId));
                }

                CloudControllerContext.getInstance().persist();

                return true;
            } catch (Exception e) {
                throw new NonExistingKubernetesHostException(e.getMessage(), e);
            }
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    @Override
    public boolean updateKubernetesMaster(KubernetesMaster kubernetesMaster)
            throws InvalidKubernetesMasterException, NonExistingKubernetesMasterException {
        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireKubernetesClusterWriteLock();
            CloudControllerUtil.validateKubernetesMaster(kubernetesMaster);
            if (log.isInfoEnabled()) {
                log.info("Updating Kubernetes master: " + kubernetesMaster);
            }
            try {
                KubernetesCluster kubernetesClusterStored = CloudControllerContext.getInstance()
                        .getKubernetesClusterContainingHost(kubernetesMaster.getHostId());

                // Update information model
                kubernetesClusterStored.setKubernetesMaster(kubernetesMaster);

                CloudControllerContext.getInstance().persist();

                if (log.isInfoEnabled()) {
                    log.info(String.format("Kubernetes master updated successfully: [id] %s",
                            kubernetesMaster.getHostId()));
                }

                return true;
            } catch (Exception e) {
                throw new InvalidKubernetesMasterException(e.getMessage(), e);
            }
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
    }

    @Override
    public boolean updateKubernetesHost(KubernetesHost kubernetesHost)
            throws InvalidKubernetesHostException, NonExistingKubernetesHostException {

        Lock lock = null;
        try {
            lock = CloudControllerContext.getInstance().acquireKubernetesClusterWriteLock();
            CloudControllerUtil.validateKubernetesHost(kubernetesHost);
            if (log.isInfoEnabled()) {
                log.info("Updating Kubernetes Host: " + kubernetesHost);
            }

            try {
                KubernetesCluster kubernetesClusterStored = CloudControllerContext.getInstance()
                        .getKubernetesClusterContainingHost(kubernetesHost.getHostId());
                KubernetesHost[] kubernetesHosts = kubernetesClusterStored.getKubernetesHosts();
                for (int i = 0; i < kubernetesHosts.length; i++) {
                    if (kubernetesHosts[i].getHostId().equals(kubernetesHost.getHostId())) {
                        // Update the information model
                        kubernetesHosts[i] = kubernetesHost;

                        if (log.isInfoEnabled()) {
                            log.info(String.format("Kubernetes host updated successfully: [id] %s",
                                    kubernetesHost.getHostId()));
                        }

                        CloudControllerContext.getInstance().updateKubernetesCluster(kubernetesClusterStored);
                        CloudControllerContext.getInstance().persist();
                        return true;
                    }
                }
            } catch (Exception e) {
                throw new InvalidKubernetesHostException(e.getMessage(), e);
            }
        } finally {
            if (lock != null) {
                CloudControllerContext.getInstance().releaseWriteLock(lock);
            }
        }
        throw new NonExistingKubernetesHostException("Kubernetes host not found [id] " + kubernetesHost.getHostId());
    }

    @Override
    public boolean addNetworkPartition(NetworkPartition networkPartition)
            throws NetworkPartitionAlreadyExistsException, InvalidNetworkPartitionException {

        handleNullObject(networkPartition, "Network Partition is null");
        handleNullObject(networkPartition.getId(), "Network Partition ID is null");

        if (log.isInfoEnabled()) {
            log.info(String.format("Adding network partition: [network-partition-id] %s", networkPartition.getId()));
        }

        String networkPartitionID = networkPartition.getId();
        if (cloudControllerContext.getNetworkPartition(networkPartitionID) != null) {
            String message = "Network partition already exists: [network-partition-id] " + networkPartitionID;
            log.error(message);
            throw new NetworkPartitionAlreadyExistsException(message);
        }

        if (networkPartition.getPartitions() != null && networkPartition.getPartitions().length != 0) {
            for (Partition partition : networkPartition.getPartitions()) {
                if (partition != null) {
                    if (log.isInfoEnabled()) {
                        log.info(String.format("Validating partition: [network-partition-id] %s [partition-id] %s",
                                networkPartition.getId(), partition.getId()));
                    }
                    // Overwrites partition provider with network partition provider
                    partition.setProvider(networkPartition.getProvider());
                    try {
                        validatePartition(partition);
                        // add Partition to partition map
                        CloudControllerContext.getInstance().addPartition(partition);
                    } catch (InvalidPartitionException e) {
                        //Following message is shown to the end user in all the the API clients(GUI/CLI/Rest API)
                        throw new InvalidNetworkPartitionException(String.format(
                                "Network partition " + " %s, is invalid since the partition %s is invalid",
                                networkPartition.getId(), partition.getId()), e);
                    }
                    if (log.isInfoEnabled()) {
                        log.info(String.format(
                                "Partition validated successfully: [network-partition-id] %s " + "[partition-id] %s",
                                networkPartition.getId(), partition.getId()));
                    }
                }
            }
        } else {
            //Following message is shown to the end user in all the the API clients(GUI/CLI/Rest API)
            throw new InvalidNetworkPartitionException(
                    String.format("Network partition: " + "%s doesn't not have any partitions ",
                            networkPartition.getId()));
        }

        // adding network partition to CC-Context
        CloudControllerContext.getInstance().addNetworkPartition(networkPartition);
        // persisting CC-Context
        try {
            CloudControllerContext.getInstance().persist();
        } catch (RegistryException e) {
            log.error("Could not add network partition [network-partition-id] " + networkPartitionID, e);
            return false;
        }
        if (log.isInfoEnabled()) {
            log.info(String.format("Network partition added successfully: [network-partition-id] %s",
                    networkPartition.getId()));
        }
        return true;
    }

    @Override
    public boolean removeNetworkPartition(String networkPartitionId) throws NetworkPartitionNotExistsException {

        try {
            if (log.isInfoEnabled()) {
                log.info(String.format("Removing network partition: [network-partition-id] %s", networkPartitionId));
            }
            handleNullObject(networkPartitionId, "Network Partition ID is null");

            if (cloudControllerContext.getNetworkPartition(networkPartitionId) == null) {
                String message = "Network partition not found: [network-partition-id] " + networkPartitionId;
                log.error(message);
                throw new NetworkPartitionNotExistsException(message);
            }

            // remove partitions from the partition map
            for (Partition partition : cloudControllerContext.getNetworkPartition(networkPartitionId).getPartitions()) {
                CloudControllerContext.getInstance().removePartition(partition.getId());
            }

            // removing from CC-Context
            CloudControllerContext.getInstance().removeNetworkPartition(networkPartitionId);
            // persisting CC-Context
            CloudControllerContext.getInstance().persist();
            if (log.isInfoEnabled()) {
                log.info(String.format("Network partition removed successfully: [network-partition-id] %s",
                        networkPartitionId));
            }
        } catch (Exception e) {
            String message = e.getMessage();
            log.error(message);
            throw new CloudControllerException(message, e);
        }
        return true;
    }

    @Override
    public boolean updateNetworkPartition(NetworkPartition networkPartition) throws NetworkPartitionNotExistsException {
        try {
            handleNullObject(networkPartition, "Network Partition is null");
            handleNullObject(networkPartition.getId(), "Network Partition ID is null");

            if (log.isInfoEnabled()) {
                log.info(String.format("Updating network partition: [network-partition-id] %s",
                        networkPartition.getId()));
            }

            String networkPartitionID = networkPartition.getId();
            if (cloudControllerContext.getNetworkPartition(networkPartitionID) == null) {
                String message = "Network partition not found: [network-partition-id] " + networkPartitionID;
                log.error(message);
                throw new NetworkPartitionNotExistsException(message);
            }

            if (networkPartition.getPartitions() != null) {
                for (Partition partition : networkPartition.getPartitions()) {
                    if (partition != null) {
                        if (log.isInfoEnabled()) {
                            log.info(String.format("Validating partition: [network-partition-id] %s [partition-id] %s",
                                    networkPartition.getId(), partition.getId()));
                        }
                        // Overwrites partition provider with network partition provider
                        partition.setProvider(networkPartition.getProvider());
                        validatePartition(partition);
                        // add Partition to partition map
                        CloudControllerContext.getInstance().addPartition(partition);
                        if (log.isInfoEnabled()) {
                            log.info(String.format("Partition validated successfully: [network-partition-id] %s "
                                    + "[partition-id] %s", networkPartition.getId(), partition.getId()));
                        }
                    }
                }
            }

            // overriding network partition to CC-Context
            CloudControllerContext.getInstance().addNetworkPartition(networkPartition);
            // persisting CC-Context
            CloudControllerContext.getInstance().persist();
            if (log.isInfoEnabled()) {
                log.info(String.format("Network partition updated successfully: [network-partition-id] %s",
                        networkPartition.getId()));
            }
            return true;
        } catch (Exception e) {
            String message = e.getMessage();
            log.error(message);
            throw new CloudControllerException(message, e);
        }
    }

    @Override
    public NetworkPartition[] getNetworkPartitions() {
        try {
            Collection<NetworkPartition> networkPartitionList = cloudControllerContext.getNetworkPartitions();
            return networkPartitionList.toArray(new NetworkPartition[networkPartitionList.size()]);
        } catch (Exception e) {
            String message = "Could not get network partitions";
            log.error(message);
            throw new CloudControllerException(message, e);
        }
    }

    @Override
    public NetworkPartition getNetworkPartition(String networkPartitionId) {
        try {
            return CloudControllerContext.getInstance().getNetworkPartition(networkPartitionId);
        } catch (Exception e) {
            String message = String
                    .format("Could not get network partition: [network-partition-id] %s", networkPartitionId);
            log.error(message);
            throw new CloudControllerException(message, e);
        }
    }

    @Override
    public String[] getIaasProviders() {
        try {
            Collection<IaasProvider> iaasProviders = CloudControllerConfig.getInstance().getIaasProviders();
            List<String> iaases = new ArrayList<>();

            for (IaasProvider iaas : iaasProviders) {
                iaases.add(iaas.getType());
            }
            return iaases.toArray(new String[iaases.size()]);
        } catch (Exception e) {
            String message = "Could not get Iaas Providers";
            log.error(message);
            throw new CloudControllerException(message, e);
        }
    }
}
