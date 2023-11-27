/*
 * Copyright 2023 AntGroup CO., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */

package com.antgroup.geaflow.analytics.service.client;

import static com.antgroup.geaflow.analytics.service.config.keys.AnalyticsClientConfigKeys.ANALYTICS_CLIENT_CONNECT_RETRY_NUM;
import static com.antgroup.geaflow.analytics.service.query.StandardError.ANALYTICS_NO_COORDINATOR;
import static com.antgroup.geaflow.analytics.service.query.StandardError.ANALYTICS_NULL_RESULT;
import static com.antgroup.geaflow.analytics.service.query.StandardError.ANALYTICS_SERVER_BUSY;
import static com.antgroup.geaflow.analytics.service.query.StandardError.ANALYTICS_SERVER_UNAVAILABLE;
import static com.antgroup.geaflow.metaserver.service.NamespaceType.DEFAULT;
import static java.util.Objects.requireNonNull;

import com.antgroup.geaflow.analytics.service.config.keys.AnalyticsClientConfigKeys;
import com.antgroup.geaflow.analytics.service.query.QueryError;
import com.antgroup.geaflow.analytics.service.query.QueryResults;
import com.antgroup.geaflow.common.config.ConfigKey;
import com.antgroup.geaflow.common.config.Configuration;
import com.antgroup.geaflow.common.config.keys.ExecutionConfigKeys;
import com.antgroup.geaflow.common.errorcode.RuntimeErrors;
import com.antgroup.geaflow.common.exception.GeaflowRuntimeException;
import com.antgroup.geaflow.common.mode.JobMode;
import com.antgroup.geaflow.common.rpc.HostAndPort;
import com.antgroup.geaflow.common.utils.SleepUtils;
import com.antgroup.geaflow.metaserver.client.MetaServerQueryClient;
import com.antgroup.geaflow.metaserver.service.NamespaceType;
import com.antgroup.geaflow.pipeline.service.ServiceType;
import com.antgroup.geaflow.service.discovery.zookeeper.ZooKeeperConfigKeys;
import com.google.common.base.Preconditions;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnalyticsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsClient.class);

    private static final NamespaceType META_SERVER_NAMESPACE = DEFAULT;
    private static final Random RANDOM = new Random(System.currentTimeMillis());
    private static final long DEFAULT_SLEEP_MS = 1000L;
    private final Configuration config;
    private final String host;
    private final int port;
    private final String zkBaseNode;
    private final String zkQuorumServer;
    private final ServiceType serviceType;
    private final boolean initChannelPools;
    private final int queryRetryNum;

    private AnalyticsServiceInfo analyticsServiceInfo;
    private MetaServerQueryClient serverQueryClient;
    private IQueryRunner queryRunner;

    public static AnalyticsClientBuilder builder() {
        return new AnalyticsClientBuilder();
    }

    private AnalyticsClient(AnalyticsClientBuilder builder) {
        this.host = builder.host;
        this.port = builder.port;
        this.initChannelPools = builder.initChannelPools;
        this.config = builder.configuration;
        this.queryRetryNum = builder.configuration.getInteger(ANALYTICS_CLIENT_CONNECT_RETRY_NUM);
        this.zkBaseNode = builder.zkBaseNode;
        this.zkQuorumServer = builder.zkQuorumServer;
        this.serviceType = ServiceType.getEnum(config);
        init();
    }

    private void init() {
        if (this.host == null) {
            checkAnalyticsClientConfig(config);
        }
        initServiceAddress();
        ClientHandlerContext clientHandlerContext =
            ClientHandlerContext.newBuilder()
                .setConfiguration(config)
                .setAnalyticsServiceInfo(this.analyticsServiceInfo)
                .enableInitChannelPools(initChannelPools)
                .build();
        this.queryRunner = ClientHandlerFactory.loadClientHandler(clientHandlerContext);
    }

    private void initServiceAddress() {
        if (host != null) {
            String serverName = String.format("%s:%d", this.host, this.port);
            HostAndPort hostAndPort = new HostAndPort(this.host, this.port);
            this.analyticsServiceInfo = new AnalyticsServiceInfo(serverName, Collections.singletonList(hostAndPort));
            LOGGER.info("init analytics client with single coordinator: {}", serverName);
            return;
        }
        serverQueryClient = MetaServerQueryClient.getClient(config);
        List<HostAndPort> serviceAddresses = getServiceAddressList();
        requireNonNull(zkBaseNode, "zkBaseNode must not is null");
        this.analyticsServiceInfo = new AnalyticsServiceInfo(zkBaseNode, serviceAddresses);
        List<String> coordinatorAddresses = serviceAddresses.stream()
            .map(hostAndPort -> String.format("%s:%d", hostAndPort.getHost(), hostAndPort.getPort())).collect(Collectors.toList());
        LOGGER.info("init analytics client with serverName {} coordinators {}", zkBaseNode,
            Arrays.toString(coordinatorAddresses.toArray()));
    }

    private List<HostAndPort> getServiceAddressList() {
        List<HostAndPort> hostAndPorts;
        try {
            hostAndPorts = serverQueryClient.queryAllServices(META_SERVER_NAMESPACE);
        } catch (Throwable e) {
            throw new GeaflowRuntimeException(String.format("zk quorm servers %s, base node %s, "
                + "query server failed", this.zkQuorumServer, this.zkBaseNode), e);
        }
        if (CollectionUtils.isEmpty(hostAndPorts)) {
            throw new GeaflowRuntimeException(String.format("zk quorm servers %s, base node %s, "
                + "query server is empty", this.zkQuorumServer, this.zkBaseNode));
        }
        return hostAndPorts;
    }

    public QueryResults executeQuery(String queryScript) {
        QueryResults result = null;
        for (int i = 0; i < this.queryRetryNum; i++) {
            result = executeQueryInternal(queryScript);
            boolean serviceBusy = false;
            boolean serviceUnavailable = false;
            if (result.getError() != null) {
                int resultErrorCode = result.getError().getCode();
                serviceBusy = resultErrorCode == ANALYTICS_SERVER_BUSY.getQueryError().getCode();
                serviceUnavailable = resultErrorCode == ANALYTICS_SERVER_UNAVAILABLE.getQueryError().getCode();
            }
            if (result.isQueryStatus() || (!serviceBusy && !serviceUnavailable)) {
                return result;
            }
            LOGGER.info("all coordinator busy or unavailable, sleep {}ms and retry", DEFAULT_SLEEP_MS);
            SleepUtils.sleepMilliSecond(DEFAULT_SLEEP_MS);
        }
        if (result == null) {
            QueryError queryError = ANALYTICS_NULL_RESULT.getQueryError();
            return new QueryResults(queryError);
        }
        return result;
    }

    private QueryResults executeQueryInternal(String queryScript) {
        int coordinatorNum = analyticsServiceInfo.getCoordinatorNum();
        if (coordinatorNum == 0) {
            QueryError queryError = ANALYTICS_NO_COORDINATOR.getQueryError();
            return new QueryResults(queryError);
        }
        int idx = RANDOM.nextInt(coordinatorNum);
        List<HostAndPort> coordinatorAddresses = analyticsServiceInfo.getCoordinatorAddresses();
        QueryResults result = null;
        for (int i = 0; i < coordinatorAddresses.size(); i++) {
            HostAndPort address = coordinatorAddresses.get(idx);
            final long start = System.currentTimeMillis();
            result = this.queryRunner.executeQuery(queryScript, address);
            LOGGER.info("coordinator {} execute queryId {} finish, cost {} ms, server type {}",
                address, result.getQueryId(), System.currentTimeMillis() - start, serviceType);
            if (!result.isQueryStatus() && result.getError().getCode() == ANALYTICS_SERVER_UNAVAILABLE.getQueryError().getCode()) {
                LOGGER.warn("coordinator execute query error, need re-init");
                this.init();
                return result;
            }
            if (!result.isQueryStatus() && result.getError().getCode() == ANALYTICS_SERVER_BUSY.getQueryError().getCode()) {
                LOGGER.warn("coordinator[{}] [{}] is busy, try next", idx, address.toString());
                idx = (idx + 1) % coordinatorNum;
                continue;
            }
            return result;
        }

        if (result != null && (!result.isQueryStatus()
            && result.getError().getCode() == ANALYTICS_SERVER_BUSY.getQueryError().getCode())) {
            QueryError queryError = ANALYTICS_SERVER_BUSY.getQueryError();
            LOGGER.error(queryError.getName());
            return new QueryResults(queryError);
        }
        throw new GeaflowRuntimeException(RuntimeErrors.INST.analyticsClientError(String.format(
            "execute query [%s] error", queryScript)));
    }

    public void shutdown() {
        if (serverQueryClient != null) {
            serverQueryClient.close();
        }
        try {
            queryRunner.close();
        } catch (Throwable e) {
            LOGGER.error("client handler close error", e);
        }
    }

    private static void checkAnalyticsClientConfig(Configuration config) {
        // Check job mode.
        checkAnalyticsClientJobMode(config);
        configIsExist(config, ZooKeeperConfigKeys.ZOOKEEPER_BASE_NODE);
        configIsExist(config, ZooKeeperConfigKeys.ZOOKEEPER_QUORUM_SERVERS);
    }

    private static void checkAnalyticsClientJobMode(Configuration config) {
        if (config.contains(ExecutionConfigKeys.JOB_MODE)) {
            JobMode jobMode = JobMode.getJobMode(config);
            Preconditions.checkArgument(JobMode.OLAP_SERVICE.equals(jobMode), "analytics job mode must set OLAP_SERVICE");
            return;
        }
        throw new GeaflowRuntimeException("analytics client config miss: " + ExecutionConfigKeys.JOB_MODE.getKey());
    }

    private static void configIsExist(Configuration config, ConfigKey configKey) {
        Preconditions.checkArgument(
            config.contains(configKey) && !config.getConfigMap().get(configKey.getKey()).isEmpty(),
            "client missing config: " + configKey.getKey() + ", description: "
                + configKey.getDescription());
    }

    public static class AnalyticsClientBuilder {

        private final Configuration configuration = new Configuration();

        private String host;

        private int port;

        private String user;

        private boolean needAuth;

        private int queryRetryNum;

        private String zkBaseNode;

        private String zkQuorumServer;

        private boolean initChannelPools;

        public AnalyticsClientBuilder withHost(String host) {
            this.host = host;
            return this;
        }

        public AnalyticsClientBuilder withPort(int port) {
            this.port = port;
            return this;
        }

        public AnalyticsClientBuilder withInitChannelPools(boolean initChannelPools) {
            this.initChannelPools = initChannelPools;
            return this;
        }

        public AnalyticsClientBuilder withNeedAuth(boolean needAuth) {
            this.needAuth = needAuth;
            return this;
        }

        public AnalyticsClientBuilder withConfiguration(Configuration configuration) {
            this.configuration.putAll(configuration.getConfigMap());
            return this;
        }

        public AnalyticsClientBuilder withUser(String user) {
            this.user = user;
            return this;
        }

        public AnalyticsClientBuilder withAnalyticsZkNode(String zkBaseNode) {
            this.configuration.put(ZooKeeperConfigKeys.ZOOKEEPER_BASE_NODE.getKey(), zkBaseNode);
            this.zkBaseNode = zkBaseNode;
            return this;
        }

        public AnalyticsClientBuilder withAnalyticsZkQuorumServers(String zkQuorumServer) {
            this.configuration.put(ZooKeeperConfigKeys.ZOOKEEPER_QUORUM_SERVERS.getKey(), zkQuorumServer);
            this.zkQuorumServer = zkQuorumServer;
            return this;
        }

        public AnalyticsClientBuilder withTimeoutMs(int timeoutMs) {
            this.configuration.put(AnalyticsClientConfigKeys.ANALYTICS_CLIENT_CONNECT_TIMEOUT_MS, String.valueOf(timeoutMs));
            return this;
        }

        public AnalyticsClientBuilder withRetryNum(int retryNum) {
            this.configuration.put(ANALYTICS_CLIENT_CONNECT_RETRY_NUM, String.valueOf(retryNum));
            this.queryRetryNum = retryNum;
            return this;
        }

        public AnalyticsClient build() {
            if (host == null) {
                checkAnalyticsClientConfig(configuration);
            }
            return new AnalyticsClient(this);
        }

    }
}