/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ranger.services.knox.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import org.apache.ranger.plugin.client.BaseClient;
import org.apache.ranger.plugin.client.HadoopException;
import org.apache.ranger.plugin.util.JsonUtilsV2;
import org.apache.ranger.plugin.util.PasswordUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class KnoxClient {
    private static final Logger LOG = LoggerFactory.getLogger(KnoxClient.class);

    private static final String EXPECTED_MIME_TYPE = "application/json";
    private static final String ERROR_MSG          = " You can still save the repository and start creating policies, but you would not be able to use autocomplete for resource names. Check ranger_admin.log for more info.";

    private final String knoxUrl;
    private final String userName;
    private final String password;

    /*
   Sample curl calls to Knox to discover topologies
   curl -ivk -u <user-name>:<user-password> https://localhost:8443/gateway/admin/api/v1/topologies
   curl -ivk -u <user-name>:<user-password> https://localhost:8443/gateway/admin/api/v1/topologies/admin
   */

    public KnoxClient(String knoxUrl, String userName, String password) {
        LOG.debug("Constructed KnoxClient with knoxUrl: {}, userName: {}", knoxUrl, userName);

        this.knoxUrl  = knoxUrl;
        this.userName = userName;
        this.password = password;
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("USAGE: java " + KnoxClient.class.getName() + " knoxUrl userName password [sslConfigFileName]");

            System.exit(1);
        }

        KnoxClient   knoxClient   = new KnoxClient(args[0], args[1], args[2]);
        List<String> topologyList = knoxClient.getTopologyList("", null);

        if ((topologyList == null) || topologyList.isEmpty()) {
            System.out.println("No knox topologies found");
        } else {
            List<String> serviceList = knoxClient.getServiceList(topologyList, "*", null);

            if ((serviceList == null) || serviceList.isEmpty()) {
                System.out.println("No services found for knox topology: ");
            } else {
                for (String service : serviceList) {
                    System.out.println("Found service for topology: " + service);
                }
            }
        }
    }

    public static Map<String, Object> connectionTest(String serviceName, Map<String, String> configs) {
        boolean             connectivityStatus = false;
        Map<String, Object> responseData       = new HashMap<>();
        KnoxClient          knoxClient         = getKnoxClient(serviceName, configs);
        List<String>        strList            = getKnoxResources(knoxClient, "", null, null, null);

        if (strList != null && !strList.isEmpty()) {
            connectivityStatus = true;
        }

        if (connectivityStatus) {
            String successMsg = "ConnectionTest Successful";

            BaseClient.generateResponseDataMap(connectivityStatus, successMsg, successMsg, null, null, responseData);
        } else {
            String failureMsg = "Unable to retrieve any topologies/services using given parameters.";

            BaseClient.generateResponseDataMap(connectivityStatus, failureMsg, failureMsg + ERROR_MSG, null, null, responseData);
        }

        return responseData;
    }

    public static KnoxClient getKnoxClient(String serviceName, Map<String, String> configs) {
        LOG.debug("Getting knoxClient for ServiceName: {}", serviceName);
        LOG.debug("configMap: {}", configs);

        KnoxClient knoxClient;

        if (configs != null && !configs.isEmpty()) {
            String knoxUrl           = configs.get("knox.url");
            String knoxAdminUser     = configs.get("username");
            String knoxAdminPassword = configs.get("password");

            knoxClient = new KnoxClient(knoxUrl, knoxAdminUser, knoxAdminPassword);
        } else {
            String msgDesc = "Could not connect as Connection ConfigMap is empty.";

            LOG.error(msgDesc);

            HadoopException hdpException = new HadoopException(msgDesc);

            hdpException.generateResponseDataMap(false, msgDesc, msgDesc + ERROR_MSG, null, null);

            throw hdpException;
        }

        return knoxClient;
    }

    public static List<String> getKnoxResources(final KnoxClient knoxClient, String topologyName, String serviceName, List<String> knoxTopologyList, List<String> knoxServiceList) {
        LOG.debug("==> KnoxClient.getKnoxResource topology: {}Service Name: {}", topologyName, serviceName);

        List<String> resultList;

        try {
            if (knoxClient == null) {
                // LOG.error("Unable to get knox resources: knoxClient is null");
                // return new ArrayList<String>();
                String msgDesc = "Unable to get knox resources: knoxClient is null.";

                LOG.error(msgDesc);

                HadoopException hdpException = new HadoopException(msgDesc);

                hdpException.generateResponseDataMap(false, msgDesc, msgDesc + ERROR_MSG, null, null);

                throw hdpException;
            }

            final Callable<List<String>> callableObj;

            if (serviceName != null) {
                final String       finalServiceNameMatching = serviceName.trim();
                final List<String> finalknoxServiceList     = knoxServiceList;
                final List<String> finalTopologyList        = knoxTopologyList;

                callableObj = () -> knoxClient.getServiceList(finalTopologyList, finalServiceNameMatching, finalknoxServiceList);
            } else {
                final String finalTopologyNameMatching = (topologyName == null) ? "" : topologyName.trim();
                final List<String> finalknoxTopologyList = knoxTopologyList;

                callableObj = () -> knoxClient.getTopologyList(finalTopologyNameMatching, finalknoxTopologyList);
            }

            resultList = timedTask(callableObj, 5, TimeUnit.SECONDS);
        } catch (HadoopException he) {
            throw he;
        } catch (Exception e) {
            String msgDesc = "Unable to get knox resources.";

            LOG.error(msgDesc, e);

            HadoopException hdpException = new HadoopException(msgDesc);

            hdpException.generateResponseDataMap(false, BaseClient.getMessage(e), msgDesc + ERROR_MSG, null, null);

            throw hdpException;
        }

        LOG.debug("<== KnoxClient.getKnoxResources() Result : {}", resultList);

        return resultList;
    }

    public static <T> T timedTask(Callable<T> callableObj, long timeout, TimeUnit timeUnit) throws Exception {
        return callableObj.call();
    }

    public List<String> getTopologyList(String topologyNameMatching, List<String> knoxTopologyList) {
        // sample URI: https://hdp.example.com:8443/gateway/admin/api/v1/topologies
        LOG.debug("Getting Knox topology list for topologyNameMatching : {}", topologyNameMatching);

        List<String> topologyList = new ArrayList<>();

        if (topologyNameMatching == null || topologyNameMatching.trim().isEmpty()) {
            topologyNameMatching = "";
        }

        String decryptedPwd = null;

        try {
            decryptedPwd = PasswordUtils.decryptPassword(password);
        } catch (Exception ex) {
            LOG.info("Password decryption failed; trying knox connection with received password string");
        } finally {
            if (decryptedPwd == null) {
                decryptedPwd = password;
            }
        }

        try {
            Client         client   = null;
            ClientResponse response = null;

            try {
                client = Client.create();

                client.addFilter(new HTTPBasicAuthFilter(userName, decryptedPwd));

                WebResource webResource = client.resource(knoxUrl);

                response = webResource.accept(EXPECTED_MIME_TYPE).get(ClientResponse.class);

                LOG.debug("Knox topology list response: {}", response);

                if (response != null) {
                    if (response.getStatus() == 200) {
                        String jsonString = response.getEntity(String.class);

                        LOG.debug("Knox topology list response JSON string: {}", jsonString);

                        JsonNode rootNode     = JsonUtilsV2.getMapper().readTree(jsonString);
                        JsonNode topologyNode = rootNode.findValue("topology");

                        if (topologyNode == null) {
                            return topologyList;
                        }

                        Iterator<JsonNode> elements = topologyNode.elements();

                        while (elements.hasNext()) {
                            JsonNode element     = elements.next();
                            JsonNode nameElement = element.get("name");

                            if (nameElement != null) {
                                String topologyName = nameElement.asText();

                                LOG.debug("Found Knox topologyName: {}", topologyName);

                                if (knoxTopologyList != null && topologyName != null && knoxTopologyList.contains(topologyNameMatching)) {
                                    continue;
                                }

                                if (topologyName != null && ("*".equals(topologyNameMatching) || topologyName.startsWith(topologyNameMatching))) {
                                    topologyList.add(topologyName);
                                }
                            }
                        }
                    } else {
                        LOG.error("Got invalid REST response from: {}, responseStatus: {}", knoxUrl, response.getStatus());
                    }
                } else {
                    String msgDesc = "Unable to get a valid response for getTopologyList() call for KnoxUrl : [" + knoxUrl + "] - got null response.";

                    LOG.error(msgDesc);

                    HadoopException hdpException = new HadoopException(msgDesc);

                    hdpException.generateResponseDataMap(false, msgDesc, msgDesc + ERROR_MSG, null, null);

                    throw hdpException;
                }
            } finally {
                if (response != null) {
                    response.close();
                }

                if (client != null) {
                    client.destroy();
                }
            }
        } catch (HadoopException he) {
            throw he;
        } catch (Throwable t) {
            String          msgDesc      = "Exception on REST call to KnoxUrl : " + knoxUrl + ".";

            HadoopException hdpException = new HadoopException(msgDesc, t);

            LOG.error(msgDesc, t);

            hdpException.generateResponseDataMap(false, BaseClient.getMessage(t), msgDesc + ERROR_MSG, null, null);

            throw hdpException;
        }

        LOG.debug("<== KnoxClient.getTopologyList() Topology Matching: {} Result : {}", topologyNameMatching, topologyList);

        return topologyList;
    }

    public List<String> getServiceList(List<String> knoxTopologyList, String serviceNameMatching, List<String> knoxServiceList) {
        // sample URI: .../admin/api/v1/topologies/<topologyName>
        LOG.debug("==> KnoxClient.getServiceList() Service Name: {}", serviceNameMatching);

        List<String> serviceList = new ArrayList<>();

        if (serviceNameMatching == null || serviceNameMatching.trim().isEmpty()) {
            serviceNameMatching = "";
        }

        String decryptedPwd = null;

        try {
            decryptedPwd = PasswordUtils.decryptPassword(password);
        } catch (Exception ex) {
            LOG.info("Password decryption failed; trying knox connection with received password string");
        } finally {
            if (decryptedPwd == null) {
                decryptedPwd = password;
            }
        }

        try {
            Client         client   = null;
            ClientResponse response = null;

            try {
                client = Client.create();

                client.addFilter(new HTTPBasicAuthFilter(userName, decryptedPwd));

                for (String topologyName : knoxTopologyList) {
                    WebResource webResource = client.resource(knoxUrl + "/" + topologyName);

                    response = webResource.accept(EXPECTED_MIME_TYPE).get(ClientResponse.class);

                    LOG.debug("Knox service lookup response: {}", response);

                    if (response != null) {
                        if (response.getStatus() == 200) {
                            String jsonString = response.getEntity(String.class);

                            LOG.debug("Knox service lookup response JSON string: {}", jsonString);

                            JsonNode rootNode     = JsonUtilsV2.getMapper().readTree(jsonString);
                            JsonNode topologyNode = rootNode.findValue("topology");

                            if (topologyNode != null) {
                                JsonNode servicesNode = topologyNode.get("service");

                                if (servicesNode != null) {
                                    Iterator<JsonNode> services = servicesNode.elements();

                                    while (services.hasNext()) {
                                        JsonNode service        = services.next();
                                        JsonNode serviceElement = service.get("role");

                                        if (serviceElement != null) {
                                            String serviceName = serviceElement.asText();

                                            LOG.debug("Knox serviceName: {}", serviceName);

                                            if (serviceName == null || (knoxServiceList != null && knoxServiceList.contains(serviceName))) {
                                                continue;
                                            }

                                            if (serviceName.startsWith(serviceNameMatching) || "*".equals(serviceNameMatching)) {
                                                serviceList.add(serviceName);
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            LOG.error("Got invalid  REST response from: {}, responsStatus: {}", knoxUrl, response.getStatus());
                        }
                    } else {
                        String msgDesc = "Unable to get a valid response for getServiceList() call for KnoxUrl : [" + knoxUrl + "] - got null response.";

                        LOG.error(msgDesc);

                        HadoopException hdpException = new HadoopException(msgDesc);

                        hdpException.generateResponseDataMap(false, msgDesc, msgDesc + ERROR_MSG, null, null);

                        throw hdpException;
                    }
                }
            } finally {
                if (response != null) {
                    response.close();
                }

                if (client != null) {
                    client.destroy();
                }
            }
        } catch (HadoopException he) {
            throw he;
        } catch (Throwable t) {
            String          msgDesc      = "Exception on REST call to KnoxUrl : " + knoxUrl + ".";

            HadoopException hdpException = new HadoopException(msgDesc, t);

            LOG.error(msgDesc, t);

            hdpException.generateResponseDataMap(false, BaseClient.getMessage(t), msgDesc + ERROR_MSG, null, null);

            throw hdpException;
        }
        return serviceList;
    }
}
