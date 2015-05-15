/*
*  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.apimgt.impl;

import org.apache.axis2.AxisFault;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.Scriptable;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.apimgt.api.APIConsumer;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.*;
import org.wso2.carbon.apimgt.api.model.Tag;
import org.wso2.carbon.apimgt.handlers.security.stub.types.APIKeyMapping;
import org.wso2.carbon.apimgt.impl.APIConstants.ApplicationStatus;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.*;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.*;
import org.wso2.carbon.apimgt.impl.workflow.*;
import org.wso2.carbon.apimgt.keymgt.client.SubscriberKeyMgtClient;
import org.wso2.carbon.apimgt.keymgt.stub.types.carbon.ApplicationKeysDTO;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.governance.api.common.dataobjects.GovernanceArtifact;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.GenericArtifactManager;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.registry.core.*;
import org.wso2.carbon.registry.core.Collection;
import org.wso2.carbon.registry.core.config.RegistryContext;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.registry.core.pagination.PaginationContext;
import org.wso2.carbon.registry.core.session.UserRegistry;
import org.wso2.carbon.registry.core.utils.RegistryUtils;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.cache.Caching;

import java.util.*;

/**
 * This class provides the core API store functionality. It is implemented in a very
 * self-contained and 'pure' manner, without taking requirements like security into account,
 * which are subject to frequent change. Due to this 'pure' nature and the significance of
 * the class to the overall API management functionality, the visibility of the class has
 * been reduced to package level. This means we can still use it for internal purposes and
 * possibly even extend it, but it's totally off the limits of the users. Users wishing to
 * programmatically access this functionality should use one of the extensions of this
 * class which is visible to them. These extensions may add additional features like
 * security to this class.
 */
class APIConsumerImpl extends AbstractAPIManager implements APIConsumer {

    private static final Log log = LogFactory.getLog(APIConsumerImpl.class);

    /* Map to Store APIs against Tag */
    private Map<String, Set<API>> taggedAPIs;
    private boolean isTenantModeStoreView;
    private String requestedTenant;
    private boolean isTagCacheEnabled;
    private Set<Tag> tagSet;
    private long tagCacheValidityTime;
    private long lastUpdatedTime;
    private Object tagCacheMutex = new Object();
    private LRUCache<String,GenericArtifactManager> genericArtifactCache = new LRUCache<String,GenericArtifactManager>(5);
    private Set<API> recentlyAddedAPI;

    public APIConsumerImpl() throws APIManagementException {
        super();
        readTagCacheConfigs();
    }

    public APIConsumerImpl(String username) throws APIManagementException {
        super(username);
        readTagCacheConfigs();
    }

    private void readTagCacheConfigs() {
        APIManagerConfiguration config = ServiceReferenceHolder.getInstance().getAPIManagerConfigurationService().
                getAPIManagerConfiguration();
        String enableTagCache = config.getFirstProperty(APIConstants.API_STORE_TAG_CACHE_DURATION);
        if (enableTagCache == null) {
            isTagCacheEnabled = false;
            tagCacheValidityTime = 0;
        } else {
            isTagCacheEnabled = true;
            tagCacheValidityTime = Long.parseLong(enableTagCache);
        }
    }

    public Subscriber getSubscriber(String subscriberId) throws APIManagementException {
        Subscriber subscriber = null;
        try {
            subscriber = apiMgtDAO.getSubscriber(subscriberId);
        } catch (APIManagementException e) {
            handleException("Failed to get Subscriber", e);
        }
        return subscriber;
    }


    /**
     * Returns the set of APIs with the given tag from the taggedAPIs Map
     *
     * @param tag
     * @return
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    public Set<API> getAPIsWithTag(String tag) throws APIManagementException {
        if (taggedAPIs != null) {
            return taggedAPIs.get(tag);
        }
        this.getAllTags();
        if (taggedAPIs != null) {
            return taggedAPIs.get(tag);
        }
        return null;
    }

    /**
     * Returns the set of APIs with the given tag from the taggedAPIs Map
     *
     * @param tag
     * @return
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    public JSONObject getPaginatedAPIsWithTag(String tag,int start,int end) throws APIManagementException {
        List<API> apiSet = new ArrayList<API>();
        Set<API> resultSet = new TreeSet<API>(new APIVersionComparator());
        Map<String, Object> results = new HashMap<String, Object>();
        Set<API> taggedAPISet = this.getAPIsWithTag(tag);
        if (taggedAPISet != null) {
            if (taggedAPISet.size() < end) {
                end = taggedAPISet.size();
            }
            int totalLength;

            apiSet.addAll(taggedAPISet);
            totalLength = apiSet.size();
            if (totalLength <= ((start + end) - 1)) {
                end = totalLength;
            } else {
                end = start + end;
            }
            for (int i = start; i < end; i++) {
                resultSet.add(apiSet.get(i));
            }

            results.put("apis", resultSet);
            results.put("length", taggedAPISet.size());
        } else {
            results.put("apis", null);
            results.put("length", 0);

        }
        return getPaginatedAPIsWithTagObject(results);
    }


    private JSONObject getPaginatedAPIsWithTagObject(Map<String, Object> results) {
        //resultMap = apiConsumer.getPaginatedAPIsWithTag(tagName, start, end);
    	JSONObject paginatedAPIswithtags = new JSONObject();
    	JSONArray apiArray = new JSONArray();
    	Set<API> apiSet = (Set<API>) results.get("apis");
        if (apiSet != null) {
            Iterator it = apiSet.iterator();
            int i = 0;
            while (it.hasNext()) {
                JSONObject currentApi = new JSONObject();
                Object apiObject = it.next();
                API api = (API) apiObject;
                APIIdentifier apiIdentifier = api.getId();
                currentApi.put("name", apiIdentifier.getApiName());
                currentApi.put("provider",
                        APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName()));
                currentApi.put("version",
                        apiIdentifier.getVersion());
                currentApi.put("description", api.getDescription());
                currentApi.put("rates",  api.getRating());
                if (api.getThumbnailUrl() == null) {
                    currentApi.put("thumbnailurl",
                            "images/api-default.png");
                } else {
                    currentApi.put("thumbnailurl",
                            APIUtil.prependWebContextRoot(api.getThumbnailUrl()));
                }
                currentApi.put("visibility", api.getVisibility());
                currentApi.put("visibleRoles",  api.getVisibleRoles());
                currentApi.put("description", api.getDescription());
                apiArray.add(i, currentApi);
                i++;
            }
            paginatedAPIswithtags.put("apis", apiArray);
            paginatedAPIswithtags.put("totalLength", results.get("length"));
        }
		return paginatedAPIswithtags;
	}

	/**
     * Returns the set of APIs with the given tag, retrieved from registry
     *
     * @param registry - Current registry; tenant/SuperTenant
     * @param tag
     * @return
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    private Set<API> getAPIsWithTag(Registry registry, String tag)
            throws APIManagementException {
        Set<API> apiSet = new TreeSet<API>(new APINameComparator());
        boolean isTenantFlowStarted = false;
        try {
        	if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)){
        		isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
        	}
            String resourceByTagQueryPath = RegistryConstants.QUERIES_COLLECTION_PATH + "/resource-by-tag";
            Map<String, String> params = new HashMap<String, String>();
            params.put("1", tag);
            params.put(RegistryConstants.RESULT_TYPE_PROPERTY_NAME, RegistryConstants.RESOURCE_UUID_RESULT_TYPE);
            Collection collection = registry.executeQuery(resourceByTagQueryPath, params);

            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(registry,
                    APIConstants.API_KEY);

            for (String row : collection.getChildren()) {
                String uuid = row.substring(row.indexOf(";") + 1, row.length());
                GenericArtifact genericArtifact = artifactManager.getGenericArtifact(uuid);
                if (genericArtifact != null && genericArtifact.getAttribute(APIConstants.API_OVERVIEW_STATUS).equals(APIConstants.PUBLISHED)) {
                apiSet.add(APIUtil.getAPI(genericArtifact));
                }
            }

        } catch (RegistryException e) {
            handleException("Failed to get API for tag " + tag, e);
        } finally {
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return apiSet;
    }

    /**
     * The method to get APIs to Store view      *
     *
     * @return Set<API>  Set of APIs
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    public Set<API> getAllPublishedAPIs(String tenantDomain) throws APIManagementException {
        SortedSet<API> apiSortedSet = new TreeSet<API>(new APINameComparator());
        SortedSet<API> apiVersionsSortedSet = new TreeSet<API>(new APIVersionComparator());
        try {
            Registry userRegistry;
            boolean isTenantMode=(tenantDomain != null);
            if ((isTenantMode && this.tenantDomain==null) || (isTenantMode && isTenantDomainNotMatching(tenantDomain))) {//Tenant store anonymous mode
                int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                        .getTenantId(tenantDomain);
                userRegistry = ServiceReferenceHolder.getInstance().
                        getRegistryService().getGovernanceUserRegistry(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME, tenantId);
            } else {
                userRegistry = registry;
            }
            this.isTenantModeStoreView = isTenantMode;
            this.requestedTenant = tenantDomain;
            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(userRegistry, APIConstants.API_KEY);
            if (artifactManager != null) {
                GenericArtifact[] genericArtifacts = artifactManager.getAllGenericArtifacts();
                if (genericArtifacts == null || genericArtifacts.length == 0) {
                    return apiSortedSet;
                }

                Map<String, API> latestPublishedAPIs = new HashMap<String, API>();
                List<API> multiVersionedAPIs = new ArrayList<API>();
                Comparator<API> versionComparator = new APIVersionComparator();
                Boolean displayMultipleVersions = APIUtil.isAllowDisplayMultipleVersions();
                Boolean displayAPIsWithMultipleStatus = APIUtil.isAllowDisplayAPIsWithMultipleStatus();
                for (GenericArtifact artifact : genericArtifacts) {
                    // adding the API provider can mark the latest API .
                    String status = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);

                    API api = null;
                    //Check the api-manager.xml config file entry <DisplayAllAPIs> value is false
                    if (!displayAPIsWithMultipleStatus) {
                        // then we are only interested in published APIs here...
                        if (status.equals(APIConstants.PUBLISHED)) {
                            api = APIUtil.getAPI(artifact);
                        }
                    } else {   // else we are interested in both deprecated/published APIs here...
                        if (status.equals(APIConstants.PUBLISHED) || status.equals(APIConstants.DEPRECATED)) {
                            api = APIUtil.getAPI(artifact);

                        }

                    }
                    if (api != null) {
                        String key;
                        //Check the configuration to allow showing multiple versions of an API true/false
                        if (!displayMultipleVersions) { //If allow only showing the latest version of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName();
                            API existingAPI = latestPublishedAPIs.get(key);
                            if (existingAPI != null) {
                                // If we have already seen an API with the same name, make sure
                                // this one has a higher version number
                                if (versionComparator.compare(api, existingAPI) > 0) {
                                    latestPublishedAPIs.put(key, api);
                                }
                            } else {
                                // We haven't seen this API before
                                latestPublishedAPIs.put(key, api);
                            }
                        } else { //If allow showing multiple versions of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName() + ":" + api.getId()
                                    .getVersion();
                            multiVersionedAPIs.add(api);
                        }
                    }
                }
                if (!displayMultipleVersions) {
                    for (API api : latestPublishedAPIs.values()) {
                        apiSortedSet.add(api);
                    }
                    return apiSortedSet;
                } else {
                    for (API api : multiVersionedAPIs) {
                        apiVersionsSortedSet.add(api);
                    }
                    return apiVersionsSortedSet;
                }
            }

        } catch (RegistryException e) {
            handleException("Failed to get all published APIs", e);
        } catch (UserStoreException e) {
            handleException("Failed to get all published APIs", e);
        }
        return apiSortedSet;

    }


    /**
     * The method to get APIs to Store view      *
     *
     * @return Set<API>  Set of APIs
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    public JSONObject getAllPaginatedPublishedAPIs(String tenantDomain,int start,int end) throws APIManagementException {

    	Boolean displayAPIsWithMultipleStatus = APIUtil.isAllowDisplayAPIsWithMultipleStatus();
    	Map<String, List<String>> listMap = new HashMap<String, List<String>>();
        //Check the api-manager.xml config file entry <DisplayAllAPIs> value is false
        if (!displayAPIsWithMultipleStatus) {
            //Create the search attribute map
            listMap.put(APIConstants.API_OVERVIEW_STATUS, new ArrayList<String>() {{
                add(APIConstants.PUBLISHED);
            }});
        } else{
            return getAllPaginatedAPIs(tenantDomain, start, end);
        }


        JSONObject result=new JSONObject();
        SortedSet<API> apiSortedSet = new TreeSet<API>(new APINameComparator());
        SortedSet<API> apiVersionsSortedSet = new TreeSet<API>(new APIVersionComparator());
        int totalLength=0;
        try {
            Registry userRegistry;
            boolean isTenantMode=(tenantDomain != null);
            if ((isTenantMode && this.tenantDomain==null) || (isTenantMode && isTenantDomainNotMatching(tenantDomain))) {//Tenant store anonymous mode
                int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                        .getTenantId(tenantDomain);
                // explicitly load the tenant's registry
      	      	APIUtil.loadTenantRegistry(tenantId);
                userRegistry = ServiceReferenceHolder.getInstance().
                        getRegistryService().getGovernanceUserRegistry(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME, tenantId);
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME);
            } else {
                userRegistry = registry;
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(this.username);
            }
            this.isTenantModeStoreView = isTenantMode;
            this.requestedTenant = tenantDomain;

            Map<String, API> latestPublishedAPIs = new HashMap<String, API>();
            List<API> multiVersionedAPIs = new ArrayList<API>();
            Comparator<API> versionComparator = new APIVersionComparator();
            Boolean displayMultipleVersions = APIUtil.isAllowDisplayMultipleVersions();

            PaginationContext.init(start, end, "ASC", APIConstants.API_OVERVIEW_NAME, Integer.MAX_VALUE);

            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(userRegistry, APIConstants.API_KEY);
            if (artifactManager != null) {
                GenericArtifact[] genericArtifacts = artifactManager.findGenericArtifacts(listMap);
                totalLength=PaginationContext.getInstance().getLength();
                if (genericArtifacts == null || genericArtifacts.length == 0) {
                    result.put("apis",apiSortedSet);
                    result.put("totalLength",totalLength);
                    return result;
                }

                for (GenericArtifact artifact : genericArtifacts) {
                    // adding the API provider can mark the latest API .
                    String status = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);

                    API api  = APIUtil.getAPI(artifact);

                    if (api != null) {
                        String key;
                        //Check the configuration to allow showing multiple versions of an API true/false
                        if (!displayMultipleVersions) { //If allow only showing the latest version of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName();
                            API existingAPI = latestPublishedAPIs.get(key);
                            if (existingAPI != null) {
                                // If we have already seen an API with the same name, make sure
                                // this one has a higher version number
                                if (versionComparator.compare(api, existingAPI) > 0) {
                                    latestPublishedAPIs.put(key, api);
                                }
                            } else {
                                // We haven't seen this API before
                                latestPublishedAPIs.put(key, api);
                            }
                        } else { //If allow showing multiple versions of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName() + ":" + api.getId()
                                    .getVersion();
                            multiVersionedAPIs.add(api);
                        }
                    }
                }
                if (!displayMultipleVersions) {
                    for (API api : latestPublishedAPIs.values()) {
                        apiSortedSet.add(api);
                    }
                    result.put("apis",apiSortedSet);
                    result.put("totalLength",totalLength);
                    return result;

                } else {
                    for (API api : multiVersionedAPIs) {
                        apiVersionsSortedSet.add(api);
                    }
                    result.put("apis",apiVersionsSortedSet);
                    result.put("totalLength",totalLength);
                    return result;

                }
            }

        } catch (RegistryException e) {
            handleException("Failed to get all published APIs", e);
        } catch (UserStoreException e) {
            handleException("Failed to get all published APIs", e);
        }finally {
            PaginationContext.destroy();
        }
        result.put("apis",apiSortedSet);
        result.put("totalLength",totalLength);
        return result;

    }

    /**
     * The method to get APIs by given status to Store view
     *
     * @return Set<API>  Set of APIs
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    @Override
    public JSONObject getAllPaginatedAPIsByStatus(String tenantDomain,
                                                  int start, int end, final String apiStatus)
            throws APIManagementException {

            Boolean displayAPIsWithMultipleStatus = APIUtil.isAllowDisplayAPIsWithMultipleStatus();
            Map<String, List<String>> listMap = new HashMap<String, List<String>>();
            //Check the api-manager.xml config file entry <DisplayAllAPIs> value is false
            if (APIConstants.PROTOTYPED.equals(apiStatus)) {
                listMap.put(APIConstants.API_OVERVIEW_STATUS, new ArrayList<String>() {{
                    add(apiStatus);
                }});
            } else {

                if (!displayAPIsWithMultipleStatus) {
                    //Create the search attribute map
                    listMap.put(APIConstants.API_OVERVIEW_STATUS, new ArrayList<String>() {{
                        add(apiStatus);
                    }});
                } else {
                    return getAllPaginatedAPIs(tenantDomain, start, end);
                }
            }

            Map<String, Object> result = new HashMap<String, Object>();
            SortedSet<API> apiSortedSet = new TreeSet<API>(new APINameComparator());
            SortedSet<API> apiVersionsSortedSet = new TreeSet<API>(new APIVersionComparator());
            int totalLength = 0;
            Registry userRegistry;
            boolean isTenantMode = (tenantDomain != null);
            try {
                if (tenantDomain != null && !org.wso2.carbon.base.MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
                } else {
                    PrivilegedCarbonContext.startTenantFlow();
                    PrivilegedCarbonContext.getThreadLocalCarbonContext().
                            setTenantDomain(org.wso2.carbon.base.MultitenantConstants.SUPER_TENANT_DOMAIN_NAME, true);

                }
            if ((isTenantMode && this.tenantDomain == null) || (isTenantMode && isTenantDomainNotMatching(tenantDomain))) {//Tenant store anonymous mode
                int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                        .getTenantId(tenantDomain);
                userRegistry = ServiceReferenceHolder.getInstance().
                        getRegistryService().getGovernanceUserRegistry(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME, tenantId);
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME);
            } else {
                userRegistry = registry;
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(this.username);
            }
            this.isTenantModeStoreView = isTenantMode;
            this.requestedTenant = tenantDomain;

            Map<String, API> latestPublishedAPIs = new HashMap<String, API>();
            List<API> multiVersionedAPIs = new ArrayList<API>();
            Comparator<API> versionComparator = new APIVersionComparator();
            Boolean displayMultipleVersions = APIUtil.isAllowDisplayMultipleVersions();

            PaginationContext.init(start, end, "ASC", APIConstants.API_OVERVIEW_NAME, Integer.MAX_VALUE);

            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(userRegistry, APIConstants.API_KEY);
            if (artifactManager != null) {
                GenericArtifact[] genericArtifacts = artifactManager.findGenericArtifacts(listMap);
                totalLength = PaginationContext.getInstance().getLength();
                if (genericArtifacts == null || genericArtifacts.length == 0) {
                    result.put("apis", apiSortedSet);
                    result.put("totalLength", totalLength);
                    return getAllPaginatedAPIsByStatusJsonObject(result);
                }

                for (GenericArtifact artifact : genericArtifacts) {
                    // adding the API provider can mark the latest API .
                    String status = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);

                    API api = APIUtil.getAPI(artifact);

                    if (api != null) {
                        String key;
                        //Check the configuration to allow showing multiple versions of an API true/false
                        if (!displayMultipleVersions) { //If allow only showing the latest version of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName();
                            API existingAPI = latestPublishedAPIs.get(key);
                            if (existingAPI != null) {
                                // If we have already seen an API with the same name, make sure
                                // this one has a higher version number
                                if (versionComparator.compare(api, existingAPI) > 0) {
                                    latestPublishedAPIs.put(key, api);
                                }
                            } else {
                                // We haven't seen this API before
                                latestPublishedAPIs.put(key, api);
                            }
                        } else { //If allow showing multiple versions of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName() + ":" + api.getId()
                                    .getVersion();
                            multiVersionedAPIs.add(api);
                        }
                    }
                }
                if (!displayMultipleVersions) {
                    for (API api : latestPublishedAPIs.values()) {
                        apiSortedSet.add(api);
                    }
                    result.put("apis", apiSortedSet);
                    result.put("totalLength", totalLength);
                    return getAllPaginatedAPIsByStatusJsonObject(result);

                } else {
                    for (API api : multiVersionedAPIs) {
                        apiVersionsSortedSet.add(api);
                    }
                    result.put("apis", apiVersionsSortedSet);
                    result.put("totalLength", totalLength);
                    return getAllPaginatedAPIsByStatusJsonObject(result);

                }
            }

        } catch (RegistryException e) {
            handleException("Failed to get all published APIs", e);
        } catch (UserStoreException e) {
            handleException("Failed to get all published APIs", e);
        } finally {
            PaginationContext.destroy();
            PrivilegedCarbonContext.endTenantFlow();

        }
        result.put("apis", apiSortedSet);
        result.put("totalLength", totalLength);
        return getAllPaginatedAPIsByStatusJsonObject(result);
    }

    private JSONObject getAllPaginatedAPIsByStatusJsonObject(
			Map<String, Object> resultMap) {
    	Set<API> apiSet;
    	JSONObject paginatedAPIsByStatusObject = new JSONObject();
    	JSONArray myn = new JSONArray();
        if (resultMap != null) {
            apiSet = (Set<API>) resultMap.get("apis");
            if (apiSet != null) {
                Iterator it = apiSet.iterator();
                int i = 0;
                while (it.hasNext()) {
                    JSONObject row = new JSONObject();
                    Object apiObject = it.next();
                    API api = (API) apiObject;
                    APIIdentifier apiIdentifier = api.getId();
                    row.put("name", apiIdentifier.getApiName());
                    row.put("provider", APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName()));
                    row.put("version", apiIdentifier.getVersion());
                    row.put("context", api.getContext());
                    row.put("status", "Deployed"); // api.getStatus().toString()
                    if (api.getThumbnailUrl() == null) {
                        //row.put("thumbnailurl", "images/api-default.png");
                    } else {
                        row.put("thumbnailurl", APIUtil.prependWebContextRoot(api.getThumbnailUrl()));
                    }
                    row.put("visibility", api.getVisibility());
                    row.put("visibleRoles", api.getVisibleRoles());
                    row.put("description", api.getDescription());
                    String apiOwner = APIUtil.replaceEmailDomainBack(api.getApiOwner());
                    if (apiOwner == null) {
                        apiOwner = APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName());
                    }
                    row.put("apiOwner", apiOwner);
                    row.put("isAdvertiseOnly", api.isAdvertiseOnly());
                    myn.add(i, row);
                    i++;
                }
                paginatedAPIsByStatusObject.put("apis", myn);
                paginatedAPIsByStatusObject.put("totalLength", resultMap.get("totalLength"));

            }
        }
		return paginatedAPIsByStatusObject;
	}

	/**
     * The method to get All PUBLISHED and DEPRECATED APIs, to Store view
     *
     * @return Set<API>  Set of APIs
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    public JSONObject getAllPaginatedAPIs(String tenantDomain,int start,int end) throws APIManagementException {
        JSONObject result=new JSONObject();
        SortedSet<API> apiSortedSet = new TreeSet<API>(new APINameComparator());
        SortedSet<API> apiVersionsSortedSet = new TreeSet<API>(new APIVersionComparator());
        int totalLength=0;
        try {
            Registry userRegistry;
            boolean isTenantMode=(tenantDomain != null);
            if ((isTenantMode && this.tenantDomain==null) || (isTenantMode && isTenantDomainNotMatching(tenantDomain))) {//Tenant store anonymous mode
                int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                        .getTenantId(tenantDomain);
                userRegistry = ServiceReferenceHolder.getInstance().
                        getRegistryService().getGovernanceUserRegistry(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME, tenantId);
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME);
            } else {
                userRegistry = registry;
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(this.username);
            }
            this.isTenantModeStoreView = isTenantMode;
            this.requestedTenant = tenantDomain;

            Map<String, API> latestPublishedAPIs = new HashMap<String, API>();
            List<API> multiVersionedAPIs = new ArrayList<API>();
            Comparator<API> versionComparator = new APIVersionComparator();
            Boolean displayMultipleVersions = APIUtil.isAllowDisplayMultipleVersions();

            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(userRegistry, APIConstants.API_KEY);

            PaginationContext.init(start, end, "ASC", APIConstants.API_OVERVIEW_NAME, Integer.MAX_VALUE);


            boolean noPublishedAPIs = false;
            if (artifactManager != null) {

            	//Create the search attribute map for PUBLISHED APIs
            	Map<String, List<String>> listMap = new HashMap<String, List<String>>();
                listMap.put(APIConstants.API_OVERVIEW_STATUS, new ArrayList<String>() {{
                        add(APIConstants.PUBLISHED);
                    }});

                GenericArtifact[] genericArtifacts = artifactManager.findGenericArtifacts(listMap);
                totalLength = PaginationContext.getInstance().getLength();
                if (genericArtifacts == null || genericArtifacts.length == 0) {
                	noPublishedAPIs = true;
                }
                int publishedAPICount = 0;
                for (GenericArtifact artifact : genericArtifacts) {
                    // adding the API provider can mark the latest API .
                    String status = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);

                    API api  = APIUtil.getAPI(artifact);

                    if (api != null) {
                        String key;
                        //Check the configuration to allow showing multiple versions of an API true/false
                        if (!displayMultipleVersions) { //If allow only showing the latest version of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName();
                            API existingAPI = latestPublishedAPIs.get(key);
                            if (existingAPI != null) {
                                // If we have already seen an API with the same name, make sure
                                // this one has a higher version number
                                if (versionComparator.compare(api, existingAPI) > 0) {
                                    latestPublishedAPIs.put(key, api);
                                }
                            } else {
                                // We haven't seen this API before
                                latestPublishedAPIs.put(key, api);
                            }
                        } else { //If allow showing multiple versions of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName() + ":" + api.getId()
                                    .getVersion();
                            multiVersionedAPIs.add(api);
                        }
                    }
                }
                if (!displayMultipleVersions) {
                	publishedAPICount = latestPublishedAPIs.size();
                } else {
                	publishedAPICount = multiVersionedAPIs.size();
                }
                if ((start + end) > publishedAPICount) {
                	if (publishedAPICount > 0) {
                		/*Starting to retrieve DEPRECATED APIs*/
                		start = 0;
                		/* publishedAPICount is always less than end*/
                		end = end - publishedAPICount;
                	} else {
                		start = start - totalLength;
                	}
                	PaginationContext.init(start, end, "ASC", APIConstants.API_OVERVIEW_NAME, Integer.MAX_VALUE);
	                //Create the search attribute map for DEPRECATED APIs
	                Map<String, List<String>> listMapForDeprecatedAPIs = new HashMap<String, List<String>>();
	                listMapForDeprecatedAPIs.put(APIConstants.API_OVERVIEW_STATUS, new ArrayList<String>() {{
	                        add(APIConstants.DEPRECATED);
	                    }});

	                GenericArtifact[] genericArtifactsForDeprecatedAPIs = artifactManager.findGenericArtifacts(listMapForDeprecatedAPIs);
	                totalLength = totalLength + PaginationContext.getInstance().getLength();
	                if ((genericArtifactsForDeprecatedAPIs == null || genericArtifactsForDeprecatedAPIs.length == 0) && noPublishedAPIs) {
	                	result.put("apis",apiSortedSet);
	                    result.put("totalLength",totalLength);
	                    return result;
	                }

	                for (GenericArtifact artifact : genericArtifactsForDeprecatedAPIs) {
	                    // adding the API provider can mark the latest API .
	                    String status = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);

	                    API api  = APIUtil.getAPI(artifact);

	                    if (api != null) {
	                        String key;
	                        //Check the configuration to allow showing multiple versions of an API true/false
	                        if (!displayMultipleVersions) { //If allow only showing the latest version of an API
	                            key = api.getId().getProviderName() + ":" + api.getId().getApiName();
	                            API existingAPI = latestPublishedAPIs.get(key);
	                            if (existingAPI != null) {
	                                // If we have already seen an API with the same name, make sure
	                                // this one has a higher version number
	                                if (versionComparator.compare(api, existingAPI) > 0) {
	                                    latestPublishedAPIs.put(key, api);
	                                }
	                            } else {
	                                // We haven't seen this API before
	                                latestPublishedAPIs.put(key, api);
	                            }
	                        } else { //If allow showing multiple versions of an API
	                            key = api.getId().getProviderName() + ":" + api.getId().getApiName() + ":" + api.getId()
	                                    .getVersion();
	                            multiVersionedAPIs.add(api);
	                        }
	                    }
	                }
                }

                if (!displayMultipleVersions) {
                    for (API api : latestPublishedAPIs.values()) {
                        apiSortedSet.add(api);
                    }
                    result.put("apis",apiSortedSet);
                    result.put("totalLength",totalLength);
                    return result;

                } else {
                    for (API api : multiVersionedAPIs) {
                        apiVersionsSortedSet.add(api);
                    }
                    result.put("apis",apiVersionsSortedSet);
                    result.put("totalLength",totalLength);
                    return result;

                }
            }

        } catch (RegistryException e) {
            handleException("Failed to get all published APIs", e);
        } catch (UserStoreException e) {
            handleException("Failed to get all published APIs", e);
        }finally {
            PaginationContext.destroy();
        }
        result.put("apis",apiSortedSet);
        result.put("totalLength",totalLength);
        return result;

    }

    private <T> T[] concatArrays(T[] first, T[] second) {
        T[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }


    public Set<API> getTopRatedAPIs(int limit) throws APIManagementException {
        int returnLimit = 0;
        SortedSet<API> apiSortedSet = new TreeSet<API>(new APINameComparator());
        try {
            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(registry, APIConstants.API_KEY);
            GenericArtifact[] genericArtifacts = artifactManager.getAllGenericArtifacts();
            if (genericArtifacts == null || genericArtifacts.length == 0) {
                return apiSortedSet;
            }
            for (GenericArtifact genericArtifact : genericArtifacts) {
                String status = genericArtifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);
                if (status.equals(APIConstants.PUBLISHED)) {
                    String artifactPath = genericArtifact.getPath();

                    float rating = registry.getAverageRating(artifactPath);
                    if (rating > APIConstants.TOP_TATE_MARGIN && (returnLimit < limit)) {
                        returnLimit++;
                        apiSortedSet.add(APIUtil.getAPI(genericArtifact, registry));
                    }
                }
            }
        } catch (RegistryException e) {
            handleException("Failed to get top rated API", e);
        }
        return apiSortedSet;
    }

    /**
     * Get the recently added APIs set
     *
     * @param limit no limit. Return everything else, limit the return list to specified value.
     * @return Set<API>
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    public JSONArray getRecentlyAddedAPIs(int limit)
            throws APIManagementException {
    	String tenantDomain = CarbonContext.getThreadLocalCarbonContext() .getTenantDomain();
        SortedSet<API> recentlyAddedAPIs = new TreeSet<API>(new APINameComparator());
        SortedSet<API> recentlyAddedAPIsWithMultipleVersions = new TreeSet<API>(new APIVersionComparator());
        Registry userRegistry = null;
        String latestAPIQueryPath = null;
        APIManagerConfiguration config = ServiceReferenceHolder.getInstance().
                getAPIManagerConfigurationService().getAPIManagerConfiguration();
        boolean isRecentlyAddedAPICacheEnabled =
              Boolean.parseBoolean(config.getFirstProperty(APIConstants.API_STORE_RECENTLY_ADDED_API_CACHE_ENABLE));

        PrivilegedCarbonContext.startTenantFlow();
        boolean isTenantFlowStarted = false;
        if (tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            isTenantFlowStarted = true;
        } else {
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME, true);
            isTenantFlowStarted = true;
        }

        try {
            boolean isTenantMode = (tenantDomain != null);
            if ((isTenantMode && this.tenantDomain == null) || (isTenantMode && isTenantDomainNotMatching(tenantDomain))) {//Tenant based store anonymous mode
                int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                        .getTenantId(tenantDomain);
                // explicitly load the tenant's registry
      	      	APIUtil.loadTenantRegistry(tenantId);
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME);
                isTenantFlowStarted = true;
                userRegistry = ServiceReferenceHolder.getInstance().
                        getRegistryService().getGovernanceUserRegistry(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME, tenantId);
            } else {
                userRegistry = registry;
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(this.username);
                isTenantFlowStarted = true;
            }
            if (isRecentlyAddedAPICacheEnabled) {
                boolean isStatusChanged = false;
                recentlyAddedAPI = (Set<API>) Caching.getCacheManager(APIConstants.API_MANAGER_CACHE_MANAGER).getCache(APIConstants.RECENTLY_ADDED_API_CACHE_NAME).get(username + ":" + tenantDomain);
                if (recentlyAddedAPI != null) {

                    for (API api : recentlyAddedAPI) {
                        try {
                            if (!APIConstants.PUBLISHED.equalsIgnoreCase(userRegistry.get(APIUtil.getAPIPath(api.getId())).getProperty(APIConstants.API_OVERVIEW_STATUS))) {
                                isStatusChanged = true;
                                break;
                            }
                        } catch (Exception ex) {
                            log.error("Error while checking API status for APP " + api.getId().getApiName() + "-" + api.getId().getVersion());
                        }

                    }
                    if (!isStatusChanged) {
                        return getRecentlyAddedAPIsArray(recentlyAddedAPI);
                    }
                }
            }

            PaginationContext.init(0, limit, "DESC", "timestamp", Integer.MAX_VALUE);

        	Map<String, List<String>> listMap = new HashMap<String, List<String>>();
        	listMap.put(APIConstants.API_OVERVIEW_STATUS, new ArrayList<String>() {{
        		add(APIConstants.PUBLISHED);
        	}});

        	//Find UUID
        	GenericArtifactManager artifactManager = APIUtil.getArtifactManager(userRegistry, APIConstants.API_KEY);
        	if (artifactManager != null) {
        		GenericArtifact[] genericArtifacts = artifactManager.findGenericArtifacts(listMap);
        		SortedSet<API> allAPIs = new TreeSet<API>(new APINameComparator());
        		for (GenericArtifact artifact : genericArtifacts) {
        			API api = APIUtil.getAPI(artifact);
        			allAPIs.add(api);
        		}

				if (!APIUtil.isAllowDisplayMultipleVersions()) {
					Map<String, API> latestPublishedAPIs = new HashMap<String, API>();
					Comparator<API> versionComparator = new APIVersionComparator();
					String key;
					for (API api : allAPIs) {
						key = api.getId().getProviderName() + ":" + api.getId().getApiName();
						API existingAPI = latestPublishedAPIs.get(key);
						if (existingAPI != null) {
							// If we have already seen an API with the same
							// name, make sure this one has a higher version
							// number
							if (versionComparator.compare(api, existingAPI) > 0) {
								latestPublishedAPIs.put(key, api);
							}
						} else {
							// We haven't seen this API before
							latestPublishedAPIs.put(key, api);
						}
					}

					for (API api : latestPublishedAPIs.values()) {
						recentlyAddedAPIs.add(api);
					}
					if (isRecentlyAddedAPICacheEnabled) {
						Caching.getCacheManager(APIConstants.API_MANAGER_CACHE_MANAGER)
						       .getCache(APIConstants.RECENTLY_ADDED_API_CACHE_NAME)
						       .put(username + ":" + tenantDomain, allAPIs);
					}
					return getRecentlyAddedAPIsArray(recentlyAddedAPIs);
				} else {
        			recentlyAddedAPIsWithMultipleVersions.addAll(allAPIs);
					if (isRecentlyAddedAPICacheEnabled) {
						Caching.getCacheManager(APIConstants.API_MANAGER_CACHE_MANAGER)
						       .getCache(APIConstants.RECENTLY_ADDED_API_CACHE_NAME)
						       .put(username + ":" + tenantDomain, allAPIs);
					}
        			return getRecentlyAddedAPIsArray(recentlyAddedAPIsWithMultipleVersions);
        		}
        	 }
        } catch (RegistryException e) {
        	handleException("Failed to get all published APIs", e);
        } catch (UserStoreException e) {
        	handleException("Failed to get all published APIs", e);
        } finally {
        	PaginationContext.destroy();
        	if (isTenantFlowStarted) {
        		PrivilegedCarbonContext.endTenantFlow();
        	}
        }
        return getRecentlyAddedAPIsArray(recentlyAddedAPIs);
    }
    

	private JSONArray getRecentlyAddedAPIsArray(Set<API> apiSet) {
		JSONArray recentApis = new JSONArray();
		Iterator it = apiSet.iterator();
		int i = 0;
		while (it.hasNext()) {
			JSONObject currentApi = new JSONObject();
			Object apiObject = it.next();
			API api = (API) apiObject;
			APIIdentifier apiIdentifier = api.getId();
			currentApi.put("name", apiIdentifier.getApiName());
			currentApi.put("provider", APIUtil
					.replaceEmailDomainBack(apiIdentifier.getProviderName()));
			currentApi.put("version", apiIdentifier.getVersion());
			currentApi.put("description", api.getDescription());
			currentApi.put("rates", api.getRating());
			if (api.getThumbnailUrl() == null) {
				currentApi.put("thumbnailurl", "images/api-default.png");
			} else {
				currentApi.put("thumbnailurl",
						APIUtil.prependWebContextRoot(api.getThumbnailUrl()));
			}
			currentApi.put("isAdvertiseOnly", api.isAdvertiseOnly());
			if (api.isAdvertiseOnly()) {
				currentApi.put("owner",
						APIUtil.replaceEmailDomainBack(api.getApiOwner()));
			}
			currentApi.put("visibility", api.getVisibility());
			currentApi.put("visibleRoles", api.getVisibleRoles());
			recentApis.add(i, currentApi);
			i++;
		}
		return recentApis;
	}

	public JSONArray getAllTags() throws APIManagementException {
		
		String requestedTenantDomain = CarbonContext.getThreadLocalCarbonContext().getTenantDomain();
        this.isTenantModeStoreView = (requestedTenantDomain != null);

        if(requestedTenantDomain != null){
            this.requestedTenant = requestedTenantDomain;
        }

        /* We keep track of the lastUpdatedTime of the TagCache to determine its freshness.
         */
        long lastUpdatedTimeAtStart = lastUpdatedTime;
        long currentTimeAtStart = System.currentTimeMillis();
        if(isTagCacheEnabled && ( (currentTimeAtStart- lastUpdatedTimeAtStart) < tagCacheValidityTime)){
            if(tagSet != null){
                return getTagArray(tagSet);
            }
        }

        Map<String, Set<API>> tempTaggedAPIs = new HashMap<String, Set<API>>();
        TreeSet<Tag> tempTagSet = new TreeSet<Tag>(new Comparator<Tag>() {
            @Override
            public int compare(Tag o1, Tag o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });
        Registry userRegistry = null;
        String tagsQueryPath = null;
        try {
            tagsQueryPath = RegistryConstants.QUERIES_COLLECTION_PATH + "/tag-summary";
            Map<String, String> params = new HashMap<String, String>();
            params.put(RegistryConstants.RESULT_TYPE_PROPERTY_NAME, RegistryConstants.TAG_SUMMARY_RESULT_TYPE);
            if ((this.isTenantModeStoreView && this.tenantDomain==null) || (this.isTenantModeStoreView && isTenantDomainNotMatching(requestedTenantDomain))) {//Tenant based store anonymous mode
                int tenantId = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                        .getTenantId(this.requestedTenant);
                userRegistry = ServiceReferenceHolder.getInstance().
                        getRegistryService().getGovernanceUserRegistry(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME, tenantId);
            } else {
                userRegistry = registry;
            }
            Collection collection = userRegistry.executeQuery(tagsQueryPath, params);
            for (String fullTag : collection.getChildren()) {
                //remove hardcoded path value
                String tagName = fullTag.substring(fullTag.indexOf(";") + 1, fullTag.indexOf(":"));

                Set<API> apisWithTag = getAPIsWithTag(userRegistry, tagName);
                    /* Add the APIs against the tag name */
                    if (apisWithTag.size() != 0) {
                        if (tempTaggedAPIs.containsKey(tagName)) {
                            for (API api : apisWithTag) {
                                tempTaggedAPIs.get(tagName).add(api);
                            }
                        } else {
                            tempTaggedAPIs.put(tagName, apisWithTag);
                        }
                    }
            }

            if(tempTaggedAPIs != null){
                Iterator<Map.Entry<String,Set<API>>>  entryIterator = tempTaggedAPIs.entrySet().iterator();
                while (entryIterator.hasNext()){
                    Map.Entry<String,Set<API>> entry = entryIterator.next();
                    tempTagSet.add(new Tag(entry.getKey(),entry.getValue().size()));
                }
            }
            synchronized (tagCacheMutex) {
                lastUpdatedTime = System.currentTimeMillis();
                this.taggedAPIs = tempTaggedAPIs;
                this.tagSet = tempTagSet;
            }

        } catch (RegistryException e) {
        	try {
        		//Before a tenant login to the store or publisher at least one time,
        		//a registry exception is thrown when the tenant store is accessed in anonymous mode.
        		//This fix checks whether query resource available in the registry. If not
        		// give a warn.
				if (!userRegistry.resourceExists(tagsQueryPath)) {
					log.warn("Failed to retrieve tags query resource at " + tagsQueryPath);
					return getTagArray(tagSet);
				}
			} catch (RegistryException e1) {
				//ignore
			}
            handleException("Failed to get all the tags", e);
        } catch (UserStoreException e) {
            handleException("Failed to get all the tags", e);
        }
        return getTagArray(tagSet);
    }

	private JSONArray getTagArray(Set<Tag> tags) {
		
		JSONArray tagArray = new JSONArray();
		if (tags != null) {
			Iterator<Tag> tagsI = tags.iterator();
			int i = 0;
            while (tagsI.hasNext()) {

                JSONObject currentTag = new JSONObject();
                Object tagObject = tagsI.next();
                Tag tag = (Tag) tagObject;

                currentTag.put("name", tag.getName());
                currentTag.put("count", tag.getNoOfOccurrences());

                tagArray.add(i, currentTag);
                i++;
            }
		}

		return tagArray;
	}

	@Override
	public JSONArray getTagsWithAttributes() throws APIManagementException {
		// Fetch the all the tags first.
		//String tenantDomain = CarbonContext.getThreadLocalCarbonContext().getTenantDomain();
		JSONArray tags = getAllTags();
		// For each and every tag get additional attributes from the registry.
		String descriptionPathPattern =
		                                APIConstants.TAGS_INFO_ROOT_LOCATION +
		                                        "/%s/description.txt";
		String thumbnailPathPattern = APIConstants.TAGS_INFO_ROOT_LOCATION + "/%s/thumbnail.png";
		//for (Tag tag : tags) {
		for (int i = 0, y = tags.size(); i < y; i++) {
			// Get the description.
			Tag tag = (Tag) tags.get(i);
			Resource descriptionResource = null;
			String descriptionPath = String.format(descriptionPathPattern, tag.getName());
			try {
				descriptionResource = registry.get(descriptionPath);
			} catch (RegistryException e) {
				log.warn(String.format("Cannot get the description for the tag '%s'", tag.getName()));
			}
			// The resource is assumed to be a byte array since its the content
			// of a text file.
			if (descriptionResource != null) {
				try {
					String description = new String((byte[]) descriptionResource.getContent());
					tag.setDescription(description);
				} catch (Exception e) {
					handleException(String.format("Cannot read content of %s", descriptionPath), e);
				}
			}
			// Checks whether the thumbnail exists.
			String thumbnailPath = String.format(thumbnailPathPattern, tag.getName());
			try {
				tag.setThumbnailExists(registry.resourceExists(thumbnailPath));
			} catch (RegistryException e) {
				log.warn(String.format("Error while querying the existence of %s", thumbnailPath),
				         e);
			}
		}
		return getTagArrayWithAttributes(tags);
	}

    private JSONArray getTagArrayWithAttributes(JSONArray tags) {
		JSONArray tagArray = new JSONArray();
		if (tags != null) {
			Iterator<Tag> tagsI = tags.iterator();
			int i = 0;
            while (tagsI.hasNext()) {

                JSONObject currentTag = new JSONObject();
                Object tagObject = tagsI.next();
                Tag tag = (Tag) tagObject;

                currentTag.put("name", tag.getName());
                currentTag.put("description", tag.getDescription());
                currentTag.put("isThumbnailExists", tag.isThumbnailExists());
                currentTag.put("count", tag.getNoOfOccurrences());

                tagArray.add(i, currentTag);
                i++;
            }
		}

		return tagArray;
	}

	public void rateAPI(APIIdentifier apiId, APIRating rating,
                        String user) throws APIManagementException {
        apiMgtDAO.addRating(apiId, rating.getRating(), user);

    }

    public void removeAPIRating(APIIdentifier apiId, String user) throws APIManagementException {
        apiMgtDAO.removeAPIRating(apiId, user);

    }

    public int getUserRating(APIIdentifier apiId, String user) throws APIManagementException {
        return apiMgtDAO.getUserRating(apiId, user);
    }

    public JSONArray getPublishedAPIsByProvider(String providerId, int limit)
            throws APIManagementException {
        SortedSet<API> apiSortedSet = new TreeSet<API>(new APINameComparator());
        SortedSet<API> apiVersionsSortedSet = new TreeSet<API>(new APIVersionComparator());
        try {
            Map<String, API> latestPublishedAPIs = new HashMap<String, API>();
            List<API> multiVersionedAPIs = new ArrayList<API>();
            Comparator<API> versionComparator = new APIVersionComparator();
            Boolean displayMultipleVersions = APIUtil.isAllowDisplayMultipleVersions();
            Boolean displayAPIsWithMultipleStatus = APIUtil.isAllowDisplayAPIsWithMultipleStatus();
            String providerPath = APIConstants.API_ROOT_LOCATION + RegistryConstants.PATH_SEPARATOR +
                    providerId;
            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(registry,
                    APIConstants.API_KEY);
            Association[] associations = registry.getAssociations(providerPath,
                    APIConstants.PROVIDER_ASSOCIATION);
            if (associations.length < limit || limit == -1) {
                limit = associations.length;
            }
            for (int i = 0; i < limit; i++) {
                Association association = associations[i];
                String apiPath = association.getDestinationPath();
                Resource resource = registry.get(apiPath);
                String apiArtifactId = resource.getUUID();
                if (apiArtifactId != null) {
                    GenericArtifact artifact = artifactManager.getGenericArtifact(apiArtifactId);
                    // check the API status
                    String status = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);

                    API api = null;
                    //Check the api-manager.xml config file entry <DisplayAllAPIs> value is false
                    if (!displayAPIsWithMultipleStatus) {
                        // then we are only interested in published APIs here...
                        if (status.equals(APIConstants.PUBLISHED)) {
                            api = APIUtil.getAPI(artifact);
                        }
                    } else {   // else we are interested in both deprecated/published APIs here...
                        if (status.equals(APIConstants.PUBLISHED) || status.equals(APIConstants.DEPRECATED)) {
                            api = APIUtil.getAPI(artifact);

                        }

                    }
                    if (api != null) {
                        String key;
                        //Check the configuration to allow showing multiple versions of an API true/false
                        if (!displayMultipleVersions) { //If allow only showing the latest version of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName();
                            API existingAPI = latestPublishedAPIs.get(key);
                            if (existingAPI != null) {
                                // If we have already seen an API with the same name, make sure
                                // this one has a higher version number
                                if (versionComparator.compare(api, existingAPI) > 0) {
                                    latestPublishedAPIs.put(key, api);
                                }
                            } else {
                                // We haven't seen this API before
                                latestPublishedAPIs.put(key, api);
                            }
                        } else { //If allow showing multiple versions of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName() + ":" + api.getId()
                                    .getVersion();
                            multiVersionedAPIs.add(api);
                        }
                    }
                } else {
                    throw new GovernanceException("artifact id is null of " + apiPath);
                }
            }
            if (!displayMultipleVersions) {
                for (API api : latestPublishedAPIs.values()) {
                    apiSortedSet.add(api);
                }
                return getPublishedAPIsByProviderArray(apiSortedSet);
            } else {
                for (API api : multiVersionedAPIs) {
                    apiVersionsSortedSet.add(api);
                }
                return getPublishedAPIsByProviderArray(apiVersionsSortedSet);
            }

        } catch (RegistryException e) {
            handleException("Failed to get Published APIs for provider : " + providerId, e);
            return null;
        }


    }

    private JSONArray getPublishedAPIsByProviderArray(
Set<API> apiSet) throws APIManagementException {
		JSONArray publishedAPIsByProvider = new JSONArray();
		if (apiSet != null) {
			Iterator it = apiSet.iterator();
			int i = 0;
			while (it.hasNext()) {
				JSONObject currentApi = new JSONObject();
				Object apiObject = it.next();
				API api = (API) apiObject;
				APIIdentifier apiIdentifier = api.getId();
				currentApi.put("name", apiIdentifier.getApiName());
				currentApi.put("provider",
						APIUtil.replaceEmailDomainBack(apiIdentifier
								.getProviderName()));
				currentApi.put("version", apiIdentifier.getVersion());
				currentApi.put("description", api.getDescription());
				// Rating should retrieve from db
				currentApi
						.put("rates", ApiMgtDAO.getAverageRating(api.getId()));
				if (api.getThumbnailUrl() == null) {
					currentApi.put("thumbnailurl", "images/api-default.png");
				} else {
					currentApi.put("thumbnailurl", APIUtil
							.prependWebContextRoot(api.getThumbnailUrl()));
				}
				currentApi.put("visibility", api.getVisibility());
				currentApi.put("visibleRoles", api.getVisibleRoles());
				publishedAPIsByProvider.add(i, currentApi);
				i++;
			}

		}
		return publishedAPIsByProvider;
	}

	public Set<API> getPublishedAPIsByProvider(String providerId, String loggedUsername, int limit, String apiOwner)
            throws APIManagementException {
        SortedSet<API> apiSortedSet = new TreeSet<API>(new APINameComparator());
        SortedSet<API> apiVersionsSortedSet = new TreeSet<API>(new APIVersionComparator());
        try {
            Map<String, API> latestPublishedAPIs = new HashMap<String, API>();
            List<API> multiVersionedAPIs = new ArrayList<API>();
            Comparator<API> versionComparator = new APIVersionComparator();
            Boolean allowMultipleVersions = APIUtil.isAllowDisplayMultipleVersions();
            Boolean showAllAPIs = APIUtil.isAllowDisplayAPIsWithMultipleStatus();

            String providerDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(providerId));
            int id = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager().getTenantId(providerDomain);
            Registry registry = ServiceReferenceHolder.getInstance().
                    getRegistryService().getGovernanceSystemRegistry(id);

            org.wso2.carbon.user.api.AuthorizationManager manager = ServiceReferenceHolder.getInstance().
                    getRealmService().getTenantUserRealm(id).
                    getAuthorizationManager();

            String providerPath = APIConstants.API_ROOT_LOCATION + RegistryConstants.PATH_SEPARATOR +
                                  providerId;
            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(registry,
                                                                                APIConstants.API_KEY);
            Association[] associations = registry.getAssociations(providerPath,
                                                                  APIConstants.PROVIDER_ASSOCIATION);
            int publishedAPICount = 0;

            for (Association association1 : associations) {

                if (publishedAPICount >= limit) {
                    break;
                }

                Association association = association1;
                String apiPath = association.getDestinationPath();

                Resource resource;
                String path = RegistryUtils.getAbsolutePath(RegistryContext.getBaseInstance(),
                                                            APIUtil.getMountedPath(RegistryContext.getBaseInstance(),
                                                                                   RegistryConstants.GOVERNANCE_REGISTRY_BASE_PATH) +
                                                            apiPath);
                boolean checkAuthorized = false;
                String userNameWithoutDomain = loggedUsername;

                String loggedDomainName = "";
                if (!"".equals(loggedUsername) &&
                    !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(super.tenantDomain)) {
                    String[] nameParts = loggedUsername.split("@");
                    loggedDomainName = nameParts[1];
                    userNameWithoutDomain = nameParts[0];
                }

                if (loggedUsername.equals("")) {
                    // Anonymous user is viewing.
                    checkAuthorized = manager.isRoleAuthorized(APIConstants.ANONYMOUS_ROLE, path, ActionConstants.GET);
                } else {
                    // Some user is logged in.
                    checkAuthorized = manager.isUserAuthorized(userNameWithoutDomain, path, ActionConstants.GET);
                }

                String apiArtifactId = null;
                if (checkAuthorized) {
                    resource = registry.get(apiPath);
                    apiArtifactId = resource.getUUID();
                }

                if (apiArtifactId != null) {
                    GenericArtifact artifact = artifactManager.getGenericArtifact(apiArtifactId);

                    // check the API status
                    String status = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);

                    API api = null;
                    //Check the api-manager.xml config file entry <DisplayAllAPIs> value is false
                    if (!showAllAPIs) {
                        // then we are only interested in published APIs here...
                        if (status.equals(APIConstants.PUBLISHED)) {
                            api = APIUtil.getAPI(artifact);
                            publishedAPICount++;
                        }
                    } else {   // else we are interested in both deprecated/published APIs here...
                        if (status.equals(APIConstants.PUBLISHED) || status.equals(APIConstants.DEPRECATED)) {
                            api = APIUtil.getAPI(artifact);
                            publishedAPICount++;

                        }

                    }
                    if (api != null) {
                        // apiOwner is the value coming from front end and compared against the API instance
                        if (apiOwner != null && !apiOwner.isEmpty()) {
                            if (APIUtil.replaceEmailDomainBack(providerId)
                                        .equals(APIUtil.replaceEmailDomainBack(apiOwner)) &&
                                api.getApiOwner() != null && !api.getApiOwner().isEmpty() &&
                                !APIUtil.replaceEmailDomainBack(apiOwner)
                                        .equals(APIUtil.replaceEmailDomainBack(api.getApiOwner()))) {
                                continue; // reject remote APIs when local admin user's API selected
                            } else if (!APIUtil.replaceEmailDomainBack(providerId).equals(
                                    APIUtil.replaceEmailDomainBack(apiOwner)) &&
                                       !APIUtil.replaceEmailDomainBack(apiOwner)
                                               .equals(APIUtil.replaceEmailDomainBack(api.getApiOwner()))) {
                                continue; // reject local admin's APIs when remote API selected
                            }
                        }
                        String key;
                        //Check the configuration to allow showing multiple versions of an API true/false
                        if (!allowMultipleVersions) { //If allow only showing the latest version of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName();
                            API existingAPI = latestPublishedAPIs.get(key);
                            if (existingAPI != null) {
                                // If we have already seen an API with the same name, make sure
                                // this one has a higher version number
                                if (versionComparator.compare(api, existingAPI) > 0) {
                                    latestPublishedAPIs.put(key, api);
                                }
                            } else {
                                // We haven't seen this API before
                                latestPublishedAPIs.put(key, api);
                            }
                        } else { //If allow showing multiple versions of an API
                            key = api.getId().getProviderName() + ":" + api.getId().getApiName() + ":" + api.getId()
                                    .getVersion();
                            multiVersionedAPIs.add(api);
                        }
                    }
                }
            }
            if (!allowMultipleVersions) {
                for (API api : latestPublishedAPIs.values()) {
                    apiSortedSet.add(api);
                }
                return apiSortedSet;
            } else {
                for (API api : multiVersionedAPIs) {
                    apiVersionsSortedSet.add(api);
                }
                return apiVersionsSortedSet;
            }

        } catch (RegistryException e) {
            handleException("Failed to get Published APIs for provider : " + providerId, e);
            return null;
        } catch (org.wso2.carbon.user.core.UserStoreException e) {
            handleException("Failed to get Published APIs for provider : " + providerId, e);
            return null;
        } catch (UserStoreException e) {
            handleException("Failed to get Published APIs for provider : " + providerId, e);
            return null;
        }

    }



    public Map<String,Object> searchPaginatedAPIs(String searchTerm, String searchType, String requestedTenantDomain,int start,int end)
            throws APIManagementException {
        Map<String,Object> result = new HashMap<String,Object>();
        try {
            Registry userRegistry;
            boolean isTenantMode=(requestedTenantDomain != null);
            int tenantIDLocal = 0;
            String userNameLocal = this.username;
            if ((isTenantMode && this.tenantDomain==null) || (isTenantMode && isTenantDomainNotMatching(requestedTenantDomain))) {//Tenant store anonymous mode
            	tenantIDLocal = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                        .getTenantId(requestedTenantDomain);
                userRegistry = ServiceReferenceHolder.getInstance().
                        getRegistryService().getGovernanceUserRegistry(CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME, tenantIDLocal);
                userNameLocal = CarbonConstants.REGISTRY_ANONNYMOUS_USERNAME;
            } else {
                userRegistry = this.registry;
                tenantIDLocal = tenantId;
            }
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setUsername(userNameLocal);

            if (APIConstants.DOCUMENTATION_SEARCH_TYPE_PREFIX.equalsIgnoreCase(searchType)) {
            	Map<Documentation, API> apiDocMap = APIUtil.searchAPIsByDoc(userRegistry, tenantIDLocal, userNameLocal, searchTerm, searchType);
            	result.put("apis", apiDocMap);
            	/*Pagination for Document search results is not supported yet, hence length is sent as end-start*/
            	if (apiDocMap.size() == 0 ) {
            		result.put("length", 0);
            	} else {
            		result.put("length", end-start);
            	}
        	}
            else if ("subcontext".equalsIgnoreCase(searchType)) {
                result = APIUtil.searchAPIsByURLPattern(userRegistry, searchTerm, start,end);               ;

            }else {
            	result=searchPaginatedAPIs(userRegistry, searchTerm, searchType,start,end);
            }

        } catch (Exception e) {
            handleException("Failed to Search APIs", e);
        }
        return result;
    }

    /**
	 * Pagination API search based on solr indexing
	 *
	 * @param registry
	 * @param searchTerm
	 * @param searchType
	 * @return
	 * @throws org.wso2.carbon.apimgt.api.APIManagementException
	 */

    public Map<String,Object> searchPaginatedAPIs(Registry registry, String searchTerm, String searchType,int start,int end) throws APIManagementException {
        SortedSet<API> apiSet = new TreeSet<API>(new APINameComparator());
        List<API> apiList = new ArrayList<API>();

        searchTerm = searchTerm.trim();
        Map<String,Object> result=new HashMap<String, Object>();
        int totalLength=0;
        String criteria=APIConstants.API_OVERVIEW_NAME;
        try {

            GenericArtifactManager artifactManager = APIUtil.getArtifactManager(registry, APIConstants.API_KEY);
            PaginationContext.init(0, 10000, "ASC", APIConstants.API_OVERVIEW_NAME, Integer.MAX_VALUE);
            if (artifactManager != null) {

                if (searchType.equalsIgnoreCase("Provider")) {
                    criteria=APIConstants.API_OVERVIEW_PROVIDER;
                    searchTerm = searchTerm.replaceAll("@", "-AT-");
                } else if (searchType.equalsIgnoreCase("Version")) {
                    criteria=APIConstants.API_OVERVIEW_VERSION;
                } else if (searchType.equalsIgnoreCase("Context")) {
                    criteria=APIConstants.API_OVERVIEW_CONTEXT;
                }else if (searchType.equalsIgnoreCase("Description")) {
                    criteria=APIConstants.API_OVERVIEW_DESCRIPTION;
                }

                //Create the search attribute map for PUBLISHED APIs
                final String searchValue = searchTerm;
                Map<String, List<String>> listMap = new HashMap<String, List<String>>();
                listMap.put(criteria, new ArrayList<String>() {{
                    add(searchValue);
                }});

                GenericArtifact[] genericArtifacts = artifactManager.findGenericArtifacts(listMap);
                totalLength = PaginationContext.getInstance().getLength();
                if (genericArtifacts == null || genericArtifacts.length == 0) {

                    result.put("apis",apiSet);
                    result.put("length",0);
                    return result;
                }

                for (GenericArtifact artifact : genericArtifacts) {
                    String status = artifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);

                    if (APIUtil.isAllowDisplayAPIsWithMultipleStatus()) {
                        if (status.equals(APIConstants.PUBLISHED) || status.equals(APIConstants.DEPRECATED)) {
                            apiList.add(APIUtil.getAPI(artifact, registry));
                        }
                    } else {
                        if (status.equals(APIConstants.PUBLISHED)) {
                            apiList.add(APIUtil.getAPI(artifact, registry));
                        }
                    }
                    totalLength=apiList.size();
                }
                if(totalLength<=((start+end)-1)){
                    end=totalLength;
                }
				for (int i = start; i < end; i++) {
					apiSet.add(apiList.get(i));

				}
            }
        } catch (RegistryException e) {
            handleException("Failed to search APIs with type", e);
        }
        result.put("apis",apiSet);
        result.put("length",totalLength);
        return result;
    }

    public Set<SubscribedAPI> getSubscribedAPIs(Subscriber subscriber) throws APIManagementException {
        Set<SubscribedAPI> originalSubscribedAPIs = null;
        Set<SubscribedAPI> subscribedAPIs = new HashSet<SubscribedAPI>();
        try {
            originalSubscribedAPIs = apiMgtDAO.getSubscribedAPIs(subscriber);
            if (originalSubscribedAPIs != null && !originalSubscribedAPIs.isEmpty()) {
                Map<String, Tier> tiers = APIUtil.getTiers(tenantId);
                for (SubscribedAPI subscribedApi : originalSubscribedAPIs) {
                    Tier tier = tiers.get(subscribedApi.getTier().getName());
                    subscribedApi.getTier().setDisplayName(tier != null ? tier.getDisplayName() : subscribedApi.getTier().getName());
                    subscribedAPIs.add(subscribedApi);
                }
            }
        } catch (APIManagementException e) {
            handleException("Failed to get APIs of " + subscriber.getName(), e);
        }
        return subscribedAPIs;
    }

    public Set<SubscribedAPI> getSubscribedAPIs(Subscriber subscriber, String applicationName) throws APIManagementException {
        Set<SubscribedAPI> subscribedAPIs = null;
        try {
            subscribedAPIs = apiMgtDAO.getSubscribedAPIs(subscriber, applicationName);
            if (subscribedAPIs != null && !subscribedAPIs.isEmpty()) {
                Map<String, Tier> tiers = APIUtil.getTiers(tenantId);
                for (SubscribedAPI subscribedApi : subscribedAPIs) {
                    Tier tier = tiers.get(subscribedApi.getTier().getName());
                    subscribedApi.getTier().setDisplayName(tier != null ? tier.getDisplayName() : subscribedApi.getTier().getName());
                    subscribedAPIs.add(subscribedApi);
                }
            }
        } catch (APIManagementException e) {
            handleException("Failed to get APIs of " + subscriber.getName() + " under application " + applicationName, e);
        }
        return subscribedAPIs;
    }

    public Set<SubscribedAPI> getPaginatedSubscribedAPIs(Subscriber subscriber, String applicationName, int startSubIndex, int endSubIndex) throws APIManagementException {
        Set<SubscribedAPI> subscribedAPIs = null;
        try {
            subscribedAPIs = apiMgtDAO.getPaginatedSubscribedAPIs(subscriber, applicationName, startSubIndex,endSubIndex);
            if(subscribedAPIs!=null && !subscribedAPIs.isEmpty()){
                Map<String, Tier> tiers=APIUtil.getTiers(tenantId);
                for(SubscribedAPI subscribedApi:subscribedAPIs) {
                    Tier tier=tiers.get(subscribedApi.getTier().getName());
                    subscribedApi.getTier().setDisplayName(tier!=null?tier.getDisplayName():subscribedApi.getTier().getName());
                    subscribedAPIs.add(subscribedApi);
                }
            }
        } catch (APIManagementException e) {
            handleException("Failed to get APIs of " + subscriber.getName() + " under application " + applicationName, e);
        }
        return subscribedAPIs;
    }

    public Integer getSubscriptionCount(Subscriber subscriber,String applicationName)
            throws APIManagementException {
        return apiMgtDAO.getSubscriptionCount(subscriber,applicationName);
    }

    public Set<APIIdentifier> getAPIByConsumerKey(String accessToken) throws APIManagementException {
        try {
            return apiMgtDAO.getAPIByConsumerKey(accessToken);
        } catch (APIManagementException e) {
            handleException("Error while obtaining API from API key", e);
        }
        return null;
    }

    public boolean isSubscribed(APIIdentifier apiIdentifier, String userId)
            throws APIManagementException {
        boolean isSubscribed;
        try {
            isSubscribed = apiMgtDAO.isSubscribed(apiIdentifier, userId);
        } catch (APIManagementException e) {
            String msg = "Failed to check if user(" + userId + ") has subscribed to " + apiIdentifier;
            log.error(msg, e);
            throw new APIManagementException(msg, e);
        }
        return isSubscribed;
    }

    public String addSubscription(APIIdentifier apiId, int applicationId, String userId)
            throws APIManagementException {

        APIIdentifier identifier =  getAPIidentifier(apiId, userId);
    	
        API api = getAPI(identifier);
        //--------------Temporary commented out until the issue of api.status properly set when publishing

        //if (api.getStatus().equals(APIStatus.PUBLISHED)) {
            int subscriptionId = apiMgtDAO.addSubscription(identifier, api.getContext(), applicationId,
                    APIConstants.SubscriptionStatus.ON_HOLD);

            boolean isTenantFlowStarted = false;
            if (tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }

            try {

                WorkflowExecutor addSubscriptionWFExecutor = WorkflowExecutorFactory.getInstance().
                        getWorkflowExecutor(WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);

                SubscriptionWorkflowDTO workflowDTO = new SubscriptionWorkflowDTO();
                workflowDTO.setStatus(WorkflowStatus.CREATED);
                workflowDTO.setCreatedTime(System.currentTimeMillis());
                workflowDTO.setTenantDomain(tenantDomain);
                workflowDTO.setTenantId(tenantId);
                workflowDTO.setExternalWorkflowReference(addSubscriptionWFExecutor.generateUUID());
                workflowDTO.setWorkflowReference(String.valueOf(subscriptionId));
                workflowDTO.setWorkflowType(WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);
                workflowDTO.setCallbackUrl(addSubscriptionWFExecutor.getCallbackURL());
                workflowDTO.setApiName(identifier.getApiName());
                workflowDTO.setApiContext(api.getContext());
                workflowDTO.setApiVersion(identifier.getVersion());
                workflowDTO.setApiProvider(identifier.getProviderName());
                workflowDTO.setTierName(identifier.getTier());
                workflowDTO.setApplicationName(apiMgtDAO.getApplicationNameFromId(applicationId));
                workflowDTO.setSubscriber(userId);
                addSubscriptionWFExecutor.execute(workflowDTO);
            } catch (WorkflowException e) {
                //If the workflow execution fails, roll back transaction by removing the subscription entry.
                apiMgtDAO.removeSubscriptionById(subscriptionId);
                log.error("Could not execute Workflow", e);
                throw new APIManagementException("Could not execute Workflow", e);
            } finally {
                if (isTenantFlowStarted) {
                    PrivilegedCarbonContext.endTenantFlow();
                }
            }

            if (APIUtil.isAPIGatewayKeyCacheEnabled()) {
                invalidateCachedKeys(applicationId, identifier);
            }
            if (log.isDebugEnabled()) {
                String logMessage = "API Name: " + identifier.getApiName() + ", API Version "+identifier.getVersion()+" subscribe by " + userId + " for app "+ apiMgtDAO.getApplicationNameFromId(applicationId);
                log.debug(logMessage);
            }

            return apiMgtDAO.getSubscriptionStatusById(subscriptionId);
        /*} else {
            throw new APIManagementException("Subscriptions not allowed on APIs in the state: " +
                    api.getStatus().getStatus());
        }  */
    }

    private APIIdentifier getAPIidentifier(APIIdentifier apiIdentifier, String userId) throws APIManagementException {
        boolean isTenantFlowStarted = false;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(apiIdentifier.getProviderName()));
            if (tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }

	        // Validation for allowed throttling tiers
            API api = getAPI(apiIdentifier);
            Set<Tier> tiers = api.getAvailableTiers();
            String tier=apiIdentifier.getTier();
            Iterator<Tier> iterator = tiers.iterator();
            boolean isTierAllowed = false;
            List<String> allowedTierList = new ArrayList<String>();
            while (iterator.hasNext()) {
                Tier t = iterator.next();
                if (t.getName() != null && (t.getName()).equals(tier)) {
                    isTierAllowed = true;
                }
                allowedTierList.add(t.getName());
            }
            if (!isTierAllowed) {
                throw new APIManagementException("Tier " + tier + " is not allowed for API " + apiIdentifier.getApiName() + "-" + apiIdentifier.getVersion() + ". Only "
                        + Arrays.toString(allowedTierList.toArray()) + " Tiers are alllowed.");
            }
            if (isTierDeneid(tier)) {
                throw new APIManagementException("Tier " + tier + " is not allowed for user " + userId);
            }
	    	// Tenant based validation for subscription
            String userDomain = MultitenantUtils.getTenantDomain(userId);
            boolean subscriptionAllowed = false;
            if (!userDomain.equals(tenantDomain)) {
                String subscriptionAvailability = api.getSubscriptionAvailability();
                if (APIConstants.SUBSCRIPTION_TO_ALL_TENANTS.equals(subscriptionAvailability)) {
                    subscriptionAllowed = true;
                } else if (APIConstants.SUBSCRIPTION_TO_SPECIFIC_TENANTS.equals(subscriptionAvailability)) {
                    String subscriptionAllowedTenants = api.getSubscriptionAvailableTenants();
                    String allowedTenants[] = null;
                    if (subscriptionAllowedTenants != null) {
                        allowedTenants = subscriptionAllowedTenants.split(",");
                        if (allowedTenants != null) {
                            for (String tenant : allowedTenants) {
                                if (tenant != null && userDomain.equals(tenant.trim())) {
                                    subscriptionAllowed = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            } else {
                subscriptionAllowed = true;
            }
            if (!subscriptionAllowed) {
                throw new APIManagementException("Subscription is not allowed for " + userDomain);
            }
            apiIdentifier.setTier(tier);
           
            return apiIdentifier;
        } catch (APIManagementException e) {
            handleException("Error while adding subscription for user: " + userId + " Reason: " + e.getMessage(), e);
            return null;
        } finally {
            if (isTenantFlowStarted) {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }
		
	}

    /**
     * This method will remove the subscription according to the provided parameters.
     *
     * @param identifier API identifier of removed API
     * @param userId, applicationId
     * @throws APIManagementException
     */
    public void removeSubscription(APIIdentifier identifier, String userId, int applicationId)
            throws APIManagementException {
        apiMgtDAO.removeSubscription(identifier, applicationId);
        if (APIUtil.isAPIGatewayKeyCacheEnabled()) {
            invalidateCachedKeys(applicationId, identifier);
        }
        if (log.isDebugEnabled()) {
            String appName = apiMgtDAO.getApplicationNameFromId(applicationId);
            String logMessage = "API Name: " + identifier.getApiName() + ", API Version " + identifier.getVersion()
                    + " subscription removed by " + userId + " from app " + appName;
            log.debug(logMessage);
        }
    }

    /**
     *
     * @param applicationId Application ID related cache keys to be cleared
     * @param identifier API identifier of changed/unsubscribed API
     * @throws APIManagementException
     */
    private void invalidateCachedKeys(int applicationId, APIIdentifier identifier) throws APIManagementException {
        APIManagerConfiguration config = ServiceReferenceHolder.getInstance().
                getAPIManagerConfigurationService().getAPIManagerConfiguration();
        if (config.getApiGatewayEnvironments().size() <= 0) {
            return;
        }

        Set<String> keys = apiMgtDAO.getApplicationKeys(applicationId);
        if (keys.size() > 0) {
            List<APIKeyMapping> mappings = new ArrayList<APIKeyMapping>();
            API api = getAPI(identifier);
            for (String key : keys) {
                APIKeyMapping mapping = new APIKeyMapping();
                //So far cache key created using API token+context+version combination, but now we generate complete key from
                //cache key invalidate logic and pass to APIAuthenticationService.
                URITemplate uriTemplate;
                Iterator<URITemplate> itr = api.getUriTemplates().iterator();
                //Iterate through URI templates for given API
                while (itr.hasNext()) {
                    uriTemplate = itr.next();
                    //Create cache keys for all possible combinations of uri templates
                    String cacheKey = key + ":" + api.getContext() + "/" + identifier.getVersion()
                            + uriTemplate.getUriTemplate() + ":"
                            + uriTemplate.getHTTPVerb() + ":"
                            + uriTemplate.getAuthType();
                    mapping.setKey(cacheKey);
                    mappings.add(mapping);
                }
            }

            try {
                Map<String, Environment> gatewayEnvs = config.getApiGatewayEnvironments();
                for (Environment environment : gatewayEnvs.values()) {
                    APIAuthenticationAdminClient client = new APIAuthenticationAdminClient(environment);
                    client.invalidateKeys(mappings);
                }
            } catch (AxisFault axisFault) {
                log.warn("Error while invalidating API keys at the gateway", axisFault);
            }
        }
    }



    public void removeSubscriber(APIIdentifier identifier, String userId)
            throws APIManagementException {
        throw new UnsupportedOperationException("Unsubscribe operation is not yet implemented");
    }

    public void updateSubscriptions(APIIdentifier identifier, String userId, int applicationId)
            throws APIManagementException {
        API api = getAPI(identifier);
        apiMgtDAO.updateSubscriptions(identifier, api.getContext(), applicationId);
    }

    public void addComment(APIIdentifier identifier, String commentText, String user) throws APIManagementException {
        apiMgtDAO.addComment(identifier, commentText, user);
    }

    public org.wso2.carbon.apimgt.api.model.Comment[] getComments(APIIdentifier identifier)
            throws APIManagementException {
        return apiMgtDAO.getComments(identifier);
    }

    private Application[] getAllApplications(String userName) throws APIManagementException {
        return apiMgtDAO.getApplications(new Subscriber(userName));
    }

    /**
     * Add a new Application from the store.
     * @param application - {@link org.wso2.carbon.apimgt.api.model.Application}
     * @param userId - {@link String}
     * @return {@link String}
     */

    public String addApplication(String appName, String userName, String tier, String callbackUrl, String description)
			throws APIManagementException {

		Subscriber subscriber = new Subscriber(username);
        Application[] apps = getAllApplications(username);

        if (apps != null) {
            for(Application app : apps) {
                if (app.getName().equals(appName)) {
                    handleException("A duplicate application already exists by the name - " + appName);
                }
            }
        }

		Application application = new Application(appName, subscriber);
		application.setTier(tier);
		application.setCallbackUrl(callbackUrl);
		application.setDescription(description);

		int applicationId = apiMgtDAO.addApplication(application, userName);

		boolean isTenantFlowStarted = false;
		if (tenantDomain != null
				&& !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME
						.equals(tenantDomain)) {
			isTenantFlowStarted = true;
			PrivilegedCarbonContext.startTenantFlow();
			PrivilegedCarbonContext.getThreadLocalCarbonContext()
					.setTenantDomain(tenantDomain, true);
		}

		try {

			WorkflowExecutor appCreationWFExecutor = WorkflowExecutorFactory
					.getInstance().getWorkflowExecutor(
							WorkflowConstants.WF_TYPE_AM_APPLICATION_CREATION);
			ApplicationWorkflowDTO appWFDto = new ApplicationWorkflowDTO();
			appWFDto.setApplication(application);

			appWFDto.setExternalWorkflowReference(appCreationWFExecutor
					.generateUUID());
			appWFDto.setWorkflowReference(String.valueOf(applicationId));
			appWFDto.setWorkflowType(WorkflowConstants.WF_TYPE_AM_APPLICATION_CREATION);
			appWFDto.setCallbackUrl(appCreationWFExecutor.getCallbackURL());
			appWFDto.setStatus(WorkflowStatus.CREATED);
			appWFDto.setTenantDomain(tenantDomain);
			appWFDto.setTenantId(tenantId);
			appWFDto.setUserName(userName);
			appWFDto.setCreatedTime(System.currentTimeMillis());

			appCreationWFExecutor.execute(appWFDto);
		} catch (WorkflowException e) {
			// If the workflow execution fails, roll back transaction by
			// removing the application entry.
			application.setId(applicationId);
			apiMgtDAO.deleteApplication(application);
			log.error("Unable to execute Application Creation Workflow", e);
			handleException("Unable to execute Application Creation Workflow",
					e);
		} finally {
			if (isTenantFlowStarted) {
				PrivilegedCarbonContext.endTenantFlow();
			}
		}
		String status = apiMgtDAO.getApplicationStatus(application.getName(),
				username);
		return status;
	}

    public void updateApplication(Application application) throws APIManagementException {
        Application app = apiMgtDAO.getApplicationById(application.getId());
        if(app != null && APIConstants.ApplicationStatus.APPLICATION_CREATED.equals(app.getStatus())){
            throw new APIManagementException("Cannot update the application while it is INACTIVE");
        }
        apiMgtDAO.updateApplication(application);
    }

    public void removeApplication(Application application) throws APIManagementException {
        APIManagerConfiguration config = ServiceReferenceHolder.getInstance().
                getAPIManagerConfigurationService().getAPIManagerConfiguration();
        boolean gatewayExists = config.getApiGatewayEnvironments().size() > 0;
        Set<SubscribedAPI> apiSet = null;
        Set<String> keys = null;
        if (gatewayExists) {
            keys = apiMgtDAO.getApplicationKeys(application.getId());
            apiSet = getSubscribedAPIs(application.getSubscriber());
        }
        apiMgtDAO.deleteApplication(application);

        if (gatewayExists && apiSet != null && keys != null) {
            Set<SubscribedAPI> removables = new HashSet<SubscribedAPI>();
            for (SubscribedAPI api : apiSet) {
                if (!api.getApplication().getName().equals(application.getName())) {
                    removables.add(api);
                }
            }

            for (SubscribedAPI api : removables) {
                apiSet.remove(api);
            }

            List<APIKeyMapping> mappings = new ArrayList<APIKeyMapping>();
            for (String key : keys) {
                for (SubscribedAPI api : apiSet) {
                    APIKeyMapping mapping = new APIKeyMapping();
                    API apiDefinition = getAPI(api.getApiId());
                    mapping.setApiVersion(api.getApiId().getVersion());
                    mapping.setContext(apiDefinition.getContext());
                    mapping.setKey(key);
                    mappings.add(mapping);
                }
            }

            if (mappings.size() > 0) {
                try {
                    Map<String, Environment> gatewayEnvs = config.getApiGatewayEnvironments();
                    for (Environment environment : gatewayEnvs.values()) {
                        APIAuthenticationAdminClient client =
                                new APIAuthenticationAdminClient(environment);
                        client.invalidateKeys(mappings);
                    }
                } catch (AxisFault axisFault) {
                    // Just logging the error is enough - We have already deleted the application
                    // which is what's important
                    log.warn("Error while invalidating API keys at the gateway", axisFault);
                }
            }
        }
    }

    @Override
    public Map<String,String> requestApprovalForApplicationRegistration(String userId, String applicationName,
                                                                        String tokenType, String callbackUrl,
                                                                        String[] allowedDomains, String validityTime,
                                                                        String tokenScope)
		    throws APIManagementException {

        Application application  = apiMgtDAO.getApplicationByName(applicationName,userId);

        if(!WorkflowStatus.APPROVED.toString().equals(application.getStatus())){
            throw new APIManagementException("Application should be approved before registering.");
        }

        boolean isTenantFlowStarted = false;
        if(tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)){
            isTenantFlowStarted = true;
            PrivilegedCarbonContext.startTenantFlow();
            PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
        }


        WorkflowExecutor appRegistrationWorkflow = null;
        ApplicationRegistrationWorkflowDTO appRegWFDto = null;
	    ApplicationKeysDTO appKeysDto = new ApplicationKeysDTO();
        appKeysDto.setTokenScope(tokenScope);

        try {
        if(APIConstants.API_KEY_TYPE_PRODUCTION.equals(tokenType)){
            appRegistrationWorkflow = WorkflowExecutorFactory.getInstance().
                    getWorkflowExecutor(WorkflowConstants.WF_TYPE_AM_APPLICATION_REGISTRATION_PRODUCTION);
            appRegWFDto = (ApplicationRegistrationWorkflowDTO)WorkflowExecutorFactory.getInstance().
                    createWorkflowDTO(WorkflowConstants.WF_TYPE_AM_APPLICATION_REGISTRATION_PRODUCTION);
        } else if(APIConstants.API_KEY_TYPE_SANDBOX.equals(tokenType)){
            appRegistrationWorkflow = WorkflowExecutorFactory.getInstance().
                    getWorkflowExecutor(WorkflowConstants.WF_TYPE_AM_APPLICATION_REGISTRATION_SANDBOX);
            appRegWFDto = (ApplicationRegistrationWorkflowDTO)WorkflowExecutorFactory.getInstance().
                    createWorkflowDTO(WorkflowConstants.WF_TYPE_AM_APPLICATION_REGISTRATION_SANDBOX);
        }

        appRegWFDto.setStatus(WorkflowStatus.CREATED);
        appRegWFDto.setCreatedTime(System.currentTimeMillis());
        appRegWFDto.setTenantDomain(tenantDomain);
        appRegWFDto.setTenantId(tenantId);
        appRegWFDto.setExternalWorkflowReference(appRegistrationWorkflow.generateUUID());
        appRegWFDto.setWorkflowReference(appRegWFDto.getExternalWorkflowReference());
        appRegWFDto.setApplication(application);
        appRegWFDto.setUserName(userId);
        appRegWFDto.setCallbackUrl(appRegistrationWorkflow.getCallbackURL());
        appRegWFDto.setDomainList(allowedDomains);
        appRegWFDto.setValidityTime(Long.parseLong(validityTime));
            appRegWFDto.setKeyDetails(appKeysDto);


            appRegistrationWorkflow.execute(appRegWFDto);

        } catch (WorkflowException e) {
            log.error("Could not execute Workflow", e);
            throw new APIManagementException("Could not execute Workflow", e);
        } finally {
            if(isTenantFlowStarted){
                PrivilegedCarbonContext.endTenantFlow();
            }
        }

        //TODO: Return  ApplicationKeysDTO or WorkflowDTO without creating a Map.To do this has to move either into
        // api module.
        Map<String,String> keyDetails = new HashMap<String, String>();
        keyDetails.put("keyState",appRegWFDto.getStatus().toString());
        ApplicationKeysDTO applicationKeysDTO = appRegWFDto.getKeyDetails();

        if(applicationKeysDTO != null){

            keyDetails.put("accessToken",applicationKeysDTO.getApplicationAccessToken());
            keyDetails.put("consumerKey",applicationKeysDTO.getConsumerKey());
            keyDetails.put("consumerSecret",applicationKeysDTO.getConsumerSecret());
            keyDetails.put("validityTime",applicationKeysDTO.getValidityTime());
            keyDetails.put("tokenScope",applicationKeysDTO.getTokenScope());

        }


        return keyDetails;
    }

    /*
    * getting key for a subscribed Application - args[] list String subscriberID, String
    * application name, String keyType
    */
    public JSONObject getApplicationKey(String username, String applicationName, String tokenType,
                                        String scopes, String validityPeriod, String callbackUrl,
                                        JSONArray accessAllowDomainsArr)
            throws APIManagementException {

        String[] accessAllowDomainsArray = new String[accessAllowDomainsArr.size()];
        for (int i=0;i<accessAllowDomainsArr.size();i++) {
            accessAllowDomainsArray[i] = (String) accessAllowDomainsArr.get(i);
        }

        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(username);
            int tenantId =
                    ServiceReferenceHolder.getInstance().getRealmService().getTenantManager()
                            .getTenantId(tenantDomain);

            if (null == validityPeriod || validityPeriod.isEmpty()) { // In case a validity period is unspecified
                long defaultValidityPeriod = APIUtil.getApplicationAccessTokenValidityPeriodInSeconds();

                if (defaultValidityPeriod < 0) {
                    validityPeriod = String.valueOf(Long.MAX_VALUE);
                } else {
                    validityPeriod = String.valueOf(defaultValidityPeriod);
                }
            }

            //checking for authorized scopes
            Set<Scope> scopeSet = new LinkedHashSet<Scope>();
            List<Scope> authorizedScopes = new ArrayList<Scope>();
            String authScopeString;
            if (scopes != null && scopes.length() != 0 &&
                !scopes.equals(APIConstants.OAUTH2_DEFAULT_SCOPE)) {
                scopeSet.addAll(getScopesByScopeKeys(scopes, tenantId));
                authorizedScopes = APIUtil.getAllowedScopesForUserApplication(username, scopeSet);
            }

            if (!authorizedScopes.isEmpty()) {
                StringBuilder scopeBuilder = new StringBuilder();
                for (Scope scope : authorizedScopes) {
                    scopeBuilder.append(scope.getKey()).append(" ");
                }
                authScopeString = scopeBuilder.toString();
            } else {
                authScopeString = APIConstants.OAUTH2_DEFAULT_SCOPE;
            }
            Map<String, String> keyDetails = requestApprovalForApplicationRegistration(
                    username, applicationName, tokenType, callbackUrl, accessAllowDomainsArray,
                    validityPeriod, authScopeString);

            JSONObject row = new JSONObject();
            String authorizedDomains = "";
            boolean first = true;
            for (String anAccessAllowDomainsArray : accessAllowDomainsArray) {
                if (first) {
                    authorizedDomains = anAccessAllowDomainsArray;
                    first = false;
                } else {
                    authorizedDomains = authorizedDomains + ", " + anAccessAllowDomainsArray;
                }
            }

            Set<Map.Entry<String, String>> entries = keyDetails.entrySet();

            for (Map.Entry<String, String> entry : entries) {
                row.put(entry.getKey(), entry.getValue());
            }

            boolean isRegenarateOptionEnabled = true;
            if (APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() < 0) {
                isRegenarateOptionEnabled = false;
            }
            row.put("enableRegenarate", isRegenarateOptionEnabled);
            row.put("accessallowdomains", authorizedDomains);
            return row;
        } catch (Exception e) {
            String msg = "Error while obtaining the application access token for the application:" + applicationName;
            log.error(msg, e);
            throw new APIManagementException(msg, e);
        }

    }


    public JSONObject createApplicationKeys(String userId, String applicationName, String tokenType, String tokenScope)
			throws APIManagementException {

		try {
			Map<String, String> keyDetails = completeApplicationRegistration(userId, applicationName, tokenType, tokenScope);
			JSONObject object = new JSONObject();

			if (keyDetails != null) {
				Iterator<Map.Entry<String, String>> entryIterator = keyDetails
						.entrySet().iterator();
				Map.Entry<String, String> entry = null;
				while (entryIterator.hasNext()) {
					entry = entryIterator.next();
					object.put(entry.getKey(), entry.getValue());
				}
				boolean isRegenarateOptionEnabled = true;
				//TODO implement getApplicationAccessTokenValidityPeriodInSeconds
				if (APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() < 0) {
					isRegenarateOptionEnabled = false;
				}
				object.put("enableRegenarate", isRegenarateOptionEnabled);

			}

			return object;
		} catch (APIManagementException e) {
			String msg = "Error while obtaining the application access token for the application:"
					+ applicationName;
			log.error(msg, e);
			throw new APIManagementException(msg, e);
		}

	}
    
    public Map<String, String> completeApplicationRegistration(String userId,
                                                               String applicationName,
                                                               String tokenType, String tokenScope)
            throws APIManagementException {

        Application application = apiMgtDAO.getApplicationByName(applicationName, userId);
        String status = apiMgtDAO.getRegistrationApprovalState(application.getId(), tokenType);
        Map<String, String> keyDetails = null;

        SubscriberKeyMgtClient keyMgtClient = APIUtil.getKeyManagementClient();
        if (APIConstants.AppRegistrationStatus.REGISTRATION_APPROVED.equals(status)) {
            ApplicationRegistrationWorkflowDTO workflowDTO = apiMgtDAO.populateAppRegistrationWorkflowDTO(application.getId());
            if (workflowDTO == null) {
                throw new APIManagementException("Couldn't populate WorkFlow details.");
            }
            try {
	            ApplicationKeysDTO dto = keyMgtClient
			            .getApplicationAccessKey(userId, application.getName(), tokenType,
			                                     application.getCallbackUrl(),
			                                     workflowDTO.getAllowedDomains(),
			                                     Long.toString(workflowDTO.getValidityTime()),
			                                     tokenScope);
                keyDetails = new HashMap<String, String>();
                keyDetails.put("accessToken", dto.getApplicationAccessToken());
                keyDetails.put("consumerKey", dto.getConsumerKey());
                keyDetails.put("consumerSecret", dto.getConsumerSecret());
                keyDetails.put("validityTime", dto.getValidityTime());
                keyDetails.put("accessallowdomains", workflowDTO.getDomainList());
                keyDetails.put("tokenScope",dto.getTokenScope());
            } catch (Exception e) {
                APIUtil.handleException("Error occurred while executing SubscriberKeyMgtClient.", e);
            }
        }
        return keyDetails;
    }

    public JSONArray getApplications(String userName) throws APIManagementException {
        return getApplicationsArray(getAllApplications(new Subscriber(userName)));
    }

    private Application[] getAllApplications(Subscriber subscriber) throws APIManagementException {
        return apiMgtDAO.getApplications(subscriber);
    }

    private JSONArray getApplicationsArray(Application[] applications) {
        JSONArray applicationArray = new JSONArray();
        if (applications != null) {
            int i = 0;
            for (Application application : applications) {
                JSONObject row = new JSONObject();
                row.put("name", application.getName());
                row.put("tier", application.getTier());
                row.put("id", application.getId());
                row.put("callbackUrl", application.getCallbackUrl());
                row.put("status", application.getStatus());
                row.put("description", application.getDescription());
                applicationArray.add(i++, row);
            }
        }
        return applicationArray;
    }

    public boolean isApplicationTokenExists(String accessToken) throws APIManagementException {
        return apiMgtDAO.isAccessTokenExists(accessToken);
    }

    public Set<SubscribedAPI> getSubscribedIdentifiers(Subscriber subscriber, APIIdentifier identifier)
            throws APIManagementException {
        Set<SubscribedAPI> subscribedAPISet = new HashSet<SubscribedAPI>();
        Set<SubscribedAPI> subscribedAPIs = getSubscribedAPIs(subscriber);
        for (SubscribedAPI api : subscribedAPIs) {
            if (api.getApiId().equals(identifier)) {
                subscribedAPISet.add(api);
            }
        }
        return subscribedAPISet;
    }


    public void updateAccessAllowDomains(String accessToken, String[] accessAllowDomains)
            throws APIManagementException {
        apiMgtDAO.updateAccessAllowDomains(accessToken, accessAllowDomains);
    }

    /**
     * Returns a list of tiers denied
     *
     * @return Set<Tier>
     */
    public JSONArray getDeniedTiers() throws APIManagementException {
        Set<String> deniedTiers = new HashSet<String>();
        String[] currentUserRoles = new String[0];
        try {
            if (tenantId != 0) {
                /* Get the roles of the Current User */
                currentUserRoles = ((UserRegistry) ((UserAwareAPIConsumer) this).registry).
                        getUserRealm().getUserStoreManager().getRoleListOfUser(((UserRegistry) this.registry).getUserName());

                Set<TierPermissionDTO> tierPermissions = apiMgtDAO.getTierPermissions(tenantId);
                for (TierPermissionDTO tierPermission : tierPermissions) {
                    String type = tierPermission.getPermissionType();

                    List<String> currentRolesList = new ArrayList<String>(Arrays.asList(currentUserRoles));
                    List<String> roles = new ArrayList<String>(Arrays.asList(tierPermission.getRoles()));
                    currentRolesList.retainAll(roles);

                    if (APIConstants.TIER_PERMISSION_ALLOW.equals(type)) {
                        /* Current User is not allowed for this Tier*/
                        if (currentRolesList.size() == 0) {
                            deniedTiers.add(tierPermission.getTierName());
                        }
                    } else {
                        /* Current User is denied for this Tier*/
                        if (currentRolesList.size() > 0) {
                            deniedTiers.add(tierPermission.getTierName());
                        }
                    }
                }
            }
        } catch (UserStoreException e) {
            log.error("cannot retrieve user role list for tenant" + tenantDomain);
        }
        return getDeniedTiersArray(deniedTiers);
    }

    private JSONArray getDeniedTiersArray(Set<String> tiers) {
    	JSONArray deniedTiersArray = new JSONArray();
        int i = 0;
        for (String tier : tiers) {
            JSONObject row = new JSONObject();
            row.put("tierName", tier);
            deniedTiersArray.add(i, row);
            i++;
        }
		return deniedTiersArray;
	}

	/**
     * Check whether given Tier is denied for the user
     *
     * @param tierName
     * @return
     * @throws org.wso2.carbon.apimgt.api.APIManagementException if failed to get the tiers
     */
    public boolean isTierDeneid(String tierName) throws APIManagementException {
        String[] currentUserRoles = new String[0];
        try {
            if (tenantId != 0) {
                /* Get the roles of the Current User */
                currentUserRoles = ((UserRegistry) ((UserAwareAPIConsumer) this).registry).
                        getUserRealm().getUserStoreManager().getRoleListOfUser(((UserRegistry) this.registry).getUserName());
                TierPermissionDTO tierPermission = apiMgtDAO.getTierPermission(tierName, tenantId);
                if (tierPermission == null) {
                    return false;
                } else {
                    List<String> currentRolesList = new ArrayList<String>(Arrays.asList(currentUserRoles));
                    List<String> roles = new ArrayList<String>(Arrays.asList(tierPermission.getRoles()));
                    currentRolesList.retainAll(roles);
                    if (APIConstants.TIER_PERMISSION_ALLOW.equals(tierPermission.getPermissionType())) {
                        if (currentRolesList.size() == 0) {
                            return true;
                        }
                    } else {
                        if (currentRolesList.size() > 0) {
                            return true;
                        }
                    }
                }
            }
        } catch (UserStoreException e) {
            log.error("cannot retrieve user role list for tenant" + tenantDomain);
        }
        return false;
    }

    /**
     * Returned an API set from a set of registry paths
     *
     * @param registry Registry object from which the APIs retrieving,
     * @param limit    Specifies the number of APIs to add.
     * @param apiPaths Array of API paths.
     * @return Set<API> set of APIs
     * @throws org.wso2.carbon.registry.core.exceptions.RegistryException
     * @throws org.wso2.carbon.apimgt.api.APIManagementException
     */
    private Set<API> getAPIs(Registry registry, int limit, String[] apiPaths)
            throws RegistryException, APIManagementException,
            UserStoreException {

        SortedSet<API> apiSortedSet = new TreeSet<API>(new APINameComparator());
        SortedSet<API> apiVersionsSortedSet = new TreeSet<API>(new APIVersionComparator());
        Boolean allowMultipleVersions =APIUtil.isAllowDisplayMultipleVersions();
        Boolean showAllAPIs = APIUtil.isAllowDisplayAPIsWithMultipleStatus();
        Map<String, API> latestPublishedAPIs = new HashMap<String, API>();
        List<API> multiVersionedAPIs = new ArrayList<API>();
        Comparator<API> versionComparator = new APIVersionComparator();

        //Find UUID
        GenericArtifactManager artifactManager = APIUtil.getArtifactManager(registry,
                APIConstants.API_KEY);
        for (int a = 0; a < apiPaths.length; a++) {
            Resource resource = registry.get(apiPaths[a]);
            if (resource != null && artifactManager != null) {
                GenericArtifact genericArtifact = artifactManager.getGenericArtifact(resource.getUUID());
                API api = null;
                String status = genericArtifact.getAttribute(APIConstants.API_OVERVIEW_STATUS);
                //Check the api-manager.xml config file entry <DisplayAllAPIs> value is false
                if (!showAllAPIs) {
                    // then we are only interested in published APIs here...
                    if (status.equals(APIConstants.PUBLISHED)) {
                        api = APIUtil.getAPI(genericArtifact, registry);
                    }
                } else {   // else we are interested in both deprecated/published APIs here...
                    if (status.equals(APIConstants.PUBLISHED) || status.equals(APIConstants.DEPRECATED)) {
                        api = APIUtil.getAPI(genericArtifact, registry);

                    }

                }
                if (api != null) {
                    String key;
                    //Check the configuration to allow showing multiple versions of an API true/false
                    if (!allowMultipleVersions) { //If allow only showing the latest version of an API
                        key = api.getId().getProviderName() + ":" + api.getId().getApiName();
                        API existingAPI = latestPublishedAPIs.get(key);
                        if (existingAPI != null) {
                            // If we have already seen an API with the same name, make sure
                            // this one has a higher version number
                            if (versionComparator.compare(api, existingAPI) > 0) {
                                latestPublishedAPIs.put(key, api);
                            }
                        } else {
                            // We haven't seen this API before
                            latestPublishedAPIs.put(key, api);
                        }
                    } else { //If allow showing multiple versions of an API
                        key = api.getId().getProviderName() + ":" + api.getId().getApiName() + ":" + api.getId()
                                .getVersion();
                        multiVersionedAPIs.add(api);
                    }
                }

            }
        }
        if (!allowMultipleVersions) {
            for (API api : latestPublishedAPIs.values()) {
                apiSortedSet.add(api);
            }
            return apiSortedSet;
        } else {
            for (API api : multiVersionedAPIs) {
                apiVersionsSortedSet.add(api);
            }
            return apiVersionsSortedSet;
        }

    }

    private boolean isAllowDisplayAllAPIs() {
        APIManagerConfiguration config = ServiceReferenceHolder.getInstance().
                getAPIManagerConfigurationService().getAPIManagerConfiguration();
        String displayAllAPIs = config.getFirstProperty(APIConstants.API_STORE_DISPLAY_ALL_APIS);
        if (displayAllAPIs == null) {
            log.warn("The configurations related to show deprecated APIs in APIStore " +
                    "are missing in api-manager.xml.");
            return false;
        }
        return Boolean.parseBoolean(displayAllAPIs);
    }

    private boolean isTenantDomainNotMatching(String tenantDomain) {
    	if (this.tenantDomain != null) {
    		return !(this.tenantDomain.equals(tenantDomain));
    	}
    	return true;
    }

    public API getAPIInfo(APIIdentifier identifier)
            throws APIManagementException {
        String apiPath = APIUtil.getAPIPath(identifier);
        try {


            Registry registry = getRegistry(identifier, apiPath);
            Resource apiResource = registry.get(apiPath);
            String artifactId = apiResource.getUUID();
            if (artifactId == null) {
                throw new APIManagementException("artifact id is null for : "+ apiPath);
            }
            GenericArtifactManager artifactManager = getGenericArtifactManager(identifier, registry);
            GovernanceArtifact apiArtifact = artifactManager.getGenericArtifact(artifactId);
            return APIUtil.getAPIInformation(apiArtifact, registry);
        } catch (RegistryException e) {
            handleException("Failed to get API from : " + apiPath, e);
            return null;
        }

    }

    private GenericArtifactManager getGenericArtifactManager(APIIdentifier identifier, Registry registry)
            throws APIManagementException {

        String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(identifier.getProviderName()));
        GenericArtifactManager manager = genericArtifactCache.get(tenantDomain);
        if (manager != null) {
            return manager;
        }
        manager = APIUtil.getArtifactManager(registry, APIConstants.API_KEY);
        genericArtifactCache.put(tenantDomain, manager);
        return manager;
    }


    private Registry getRegistry(APIIdentifier identifier, String apiPath)
            throws APIManagementException {
        Registry passRegistry;
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(identifier.getProviderName()));
            if (!tenantDomain.equals(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) {
                int id = ServiceReferenceHolder.getInstance().getRealmService().getTenantManager().getTenantId(tenantDomain);
                // explicitly load the tenant's registry
                APIUtil.loadTenantRegistry(id);
                passRegistry = ServiceReferenceHolder.getInstance().getRegistryService().getGovernanceSystemRegistry(id);
            } else {
                if (this.tenantDomain != null && !this.tenantDomain.equals(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)) {
                    // explicitly load the tenant's registry
                    APIUtil.loadTenantRegistry(MultitenantConstants.SUPER_TENANT_ID);
                    passRegistry = ServiceReferenceHolder.getInstance().getRegistryService().getGovernanceUserRegistry(
                            identifier.getProviderName(), MultitenantConstants.SUPER_TENANT_ID);
                } else {
                    passRegistry = this.registry;
                }
            }
        } catch (RegistryException e) {
            handleException("Failed to get API from registry on path of : " +apiPath, e);
            return null;
        } catch (UserStoreException e) {
            handleException("Failed to get API from registry on path of : "+ apiPath, e);
            return null;
        }
        return passRegistry;
    }

	@Override
	public Set<API> searchAPI(String searchTerm, String searchType, String tenantDomain)
	                                                                                    throws APIManagementException {
		// TODO Auto-generated method stub
		return null;
	}

	public Set<Scope> getScopesBySubscribedAPIs(List<APIIdentifier> identifiers)
			throws APIManagementException {
		return apiMgtDAO.getScopesBySubscribedAPIs(identifiers);
	}

	public String getScopesByToken(String accessToken) throws APIManagementException {
		return apiMgtDAO.getScopesByToken(accessToken);
	}

	public Set<Scope> getScopesByScopeKeys(String scopeKeys, int tenantId)
			throws APIManagementException {
		return apiMgtDAO.getScopesByScopeKeys(scopeKeys, tenantId);
	}

	@Override
	public JSONArray getSubscriptions(String providerName, String apiName, String version, String user) throws APIManagementException{
		
        APIIdentifier apiIdentifier = new APIIdentifier(APIUtil.replaceEmailDomain(providerName), apiName, version);
        Subscriber subscriber = new Subscriber(user);
        JSONArray subscriptions = new JSONArray();
        Set<SubscribedAPI> apis = getSubscribedIdentifiers(subscriber, apiIdentifier);
        
        int i = 0;
        if (apis != null) {
            for (SubscribedAPI api : apis) {
                JSONObject row = new JSONObject();
                row.put("application", api.getApplication().getName());
                row.put("applicationId", api.getApplication().getId());
                row.put("prodKey", getKey(api, APIConstants.API_KEY_TYPE_PRODUCTION));
                row.put("sandboxKey", getKey(api, APIConstants.API_KEY_TYPE_SANDBOX));
                ArrayList<APIKey> keys = (ArrayList<APIKey>) api.getApplication().getKeys();
                for(APIKey key : keys){
                    row.put(key.getType()+"_KEY", key.getAccessToken());
                }
                subscriptions.add(i++, row);
            }
        }
		return subscriptions;
		
	}
	
	@Override
	public JSONObject getAllSubscriptions(String userName, String appName,
			int startSubIndex, int endSubIndex) throws APIManagementException {
        JSONArray applicationList = new JSONArray();
        Integer subscriptionCount = 0;
        JSONObject result = new JSONObject();
        boolean isTenantFlowStarted = false;

        long startTime = 0;
        if(log.isDebugEnabled()){
            startTime = System.currentTimeMillis();
        }
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(username));
            if (tenantDomain != null &&
                !org.wso2.carbon.base.MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }

            Subscriber subscriber = new Subscriber(username);
            Application[] applications = getAllApplications(new Subscriber(username));

            if (applications != null) {
                int i = 0;
                for (Application application : applications) {
                    long startLoop = 0;
                    if (log.isDebugEnabled()) {
                        startLoop = System.currentTimeMillis();
                    }
                    JSONArray apisArray = new JSONArray();
                    Set<Scope> scopeSet = new LinkedHashSet<Scope>();
                    JSONArray scopesArray = new JSONArray();

                    if ((appName == null || "".equals(appName)) ||
                        appName.equals(application.getName())) {

                        //get subscribed APIs set as per the starting and ending indexes for application.
                        Set<SubscribedAPI> subscribedAPIs =
                                getPaginatedSubscribedAPIs(subscriber, application.getName(), startSubIndex, endSubIndex);
                        subscriptionCount = getSubscriptionCount(subscriber, application.getName());

                        List<APIIdentifier> identifiers = new ArrayList<APIIdentifier>();
                        for (SubscribedAPI subscribedAPI : subscribedAPIs) {
                            addAPIObj(subscribedAPI, apisArray, application);
                            identifiers.add(subscribedAPI.getApiId());

                        }

                        if (!identifiers.isEmpty()) {
                            //get scopes for subscribed apis
                            scopeSet = getScopesBySubscribedAPIs(identifiers);
                            for (Scope scope : scopeSet) {
                                JSONObject scopeObj = new JSONObject();
                                scopeObj.put("scopeKey", scope.getKey());
                                scopeObj.put("scopeName",scope.getName());
                                scopesArray.add(scopeObj);
                            }
                        }

                        if (log.isDebugEnabled()) {
                            log.debug("getSubscribedAPIs loop took : " +
                                      (System.currentTimeMillis() - startLoop) + "ms");
                        }
                    }

                    if (ApplicationStatus.APPLICATION_APPROVED.equals(application.getStatus())) {
                        JSONObject appObj = new JSONObject();
                        appObj.put("id",application.getId());
                        appObj.put("name",application.getName());
                        appObj.put("callbackUrl", application.getCallbackUrl());
                        APIKey prodKey = APIUtil.getAppKey(application, APIConstants.API_KEY_TYPE_PRODUCTION);

                        boolean prodEnableRegenarateOption = true;
                        String prodKeyScope = "";
                        if (prodKey != null && prodKey.getTokenScope() != null) {
                            //convert scope keys to names
                            prodKeyScope =getScopeNamesbyKey(prodKey.getTokenScope(), scopeSet);
                        }

                        boolean prodEnableReganarateOption = true;
                        if (prodKey != null && prodKey.getAccessToken() != null) {
                            appObj.put("prodKey",prodKey.getAccessToken());
                            appObj.put("prodKeyScope", prodKeyScope);
                            appObj.put("prodConsumerKey", prodKey.getConsumerKey());
                            appObj.put("prodConsumerSecret", prodKey.getConsumerSecret());
                            if (prodKey.getValidityPeriod() == Long.MAX_VALUE) {
                                prodEnableRegenarateOption = false;
                            }
                            appObj.put("prodRegenarateOption", prodEnableRegenarateOption);
                            appObj.put("prodAuthorizedDomains", prodKey.getAuthorizedDomains());

                            if (APIUtil.isApplicationAccessTokenNeverExpire(prodKey.getValidityPeriod())) {
                                appObj.put("prodValidityTime", -1);
                            } else {
                                appObj.put("prodValidityTime",  prodKey.getValidityPeriod());
                            }
                        } else if (prodKey != null) {
                            appObj.put("prodKey", null);
                            appObj.put("prodKeyScope", null);
                            appObj.put("prodConsumerKey", null);
                            appObj.put("prodConsumerSecret",  null);
                            appObj.put("prodRegenarateOption", prodEnableRegenarateOption);
                            appObj.put("prodAuthorizedDomains",null);
                            if (APIUtil.isApplicationAccessTokenNeverExpire(
                                    APIUtil.getApplicationAccessTokenValidityPeriodInSeconds())) {
                                appObj.put("prodValidityTime",-1);
                            } else {
                                appObj.put("prodValidityTime",
                                           APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() * 1000);
                            }
                            appObj.put("prodKeyState", prodKey.getState());
                        } else {
                            appObj.put("prodKey", null);
                            appObj.put("prodKeyScope", null);
                            appObj.put("prodConsumerKey", null);
                            appObj.put("prodConsumerSecret", null);
                            appObj.put("prodRegenarateOption", prodEnableRegenarateOption);
                            appObj.put("prodAuthorizedDomains", null);
                            if (APIUtil.isApplicationAccessTokenNeverExpire(
                                    APIUtil.getApplicationAccessTokenValidityPeriodInSeconds())) {
                                appObj.put("prodValidityTime",  -1);
                            } else {
                                appObj.put("prodValidityTime",
                                           APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() * 1000);
                            }
                        }

                        APIKey sandboxKey = APIUtil.getAppKey(application, APIConstants.API_KEY_TYPE_SANDBOX);
                        boolean sandEnableRegenarateOption = true;

                        String sandKeyScope="";
                        if (sandboxKey != null && sandboxKey.getTokenScope() != null){
                            //convert scope keys to names
                            sandKeyScope = getScopeNamesbyKey(sandboxKey.getTokenScope(), scopeSet);
                        }

                        if (sandboxKey != null && sandboxKey.getConsumerKey() != null) {
                            appObj.put("sandboxKey", sandboxKey.getAccessToken());
                            appObj.put("sandKeyScope", sandKeyScope);
                            appObj.put("sandboxConsumerKey", sandboxKey.getConsumerKey());
                            appObj.put("sandboxConsumerSecret", sandboxKey.getConsumerSecret());
                            appObj.put("sandboxKeyState", sandboxKey.getState());
                            if (sandboxKey.getValidityPeriod() == Long.MAX_VALUE) {
                                sandEnableRegenarateOption = false;
                            }
                            appObj.put("sandRegenarateOption", sandEnableRegenarateOption);
                            appObj.put("sandboxAuthorizedDomains", sandboxKey.getAuthorizedDomains());
                            if (APIUtil.isApplicationAccessTokenNeverExpire(sandboxKey.getValidityPeriod())) {
                                if (tenantDomain != null &&
                                    !org.wso2.carbon.base.MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                                    isTenantFlowStarted = true;
                                    PrivilegedCarbonContext.startTenantFlow();
                                    PrivilegedCarbonContext.getThreadLocalCarbonContext()
                                            .setTenantDomain(tenantDomain, true);
                                }
                                appObj.put("sandValidityTime", -1);
                            } else {
                                appObj.put("sandValidityTime", sandboxKey.getValidityPeriod());
                            }
                        } else if (sandboxKey != null) {
                            appObj.put("sandboxKey", null);
                            appObj.put("sandKeyScope", null);
                            appObj.put("sandboxConsumerKey",null);
                            appObj.put("sandboxConsumerSecret",null);
                            appObj.put("sandRegenarateOption", sandEnableRegenarateOption);
                            appObj.put("sandboxAuthorizedDomains",  null);
                            appObj.put("sandboxKeyState", sandboxKey.getState());
                            if (APIUtil.isApplicationAccessTokenNeverExpire(
                                    APIUtil.getApplicationAccessTokenValidityPeriodInSeconds())) {
                                appObj.put("sandValidityTime",  -1);
                            } else {
                                appObj.put("sandValidityTime",
                                           APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() * 1000);
                            }
                        } else {
                            appObj.put("sandboxKey", null);
                            appObj.put("sandKeyScope", null);
                            appObj.put("sandboxConsumerKey", null);
                            appObj.put("sandboxConsumerSecret", null);
                            appObj.put("sandRegenarateOption", sandEnableRegenarateOption);
                            appObj.put("sandboxAuthorizedDomains", null);
                            if (APIUtil.isApplicationAccessTokenNeverExpire(
                                    APIUtil.getApplicationAccessTokenValidityPeriodInSeconds())) {
                                appObj.put("sandValidityTime",-1);
                            } else {
                                appObj.put("sandValidityTime",
                                           APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() * 1000);
                            }
                        }

                        appObj.put("subscriptions",  apisArray);
                        appObj.put("scopes", scopesArray);
                        applicationList.add(i++, appObj);
                        result.put("applications", applicationList);
                        result.put("totalLength", subscriptionCount);
                    }
                }
            }
        } catch (APIManagementException e) {
            handleException("Error while obtaining application data", e);
        } finally {
            if (isTenantFlowStarted) {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("jsFunction_getMySubscriptionDetail took : " + (System.currentTimeMillis() - startTime) + "ms");
        }

        return result;
	}

    private String getScopeNamesbyKey(String scopeKey, Set<Scope> availableScopeSet) {
        //convert scope keys to names
        StringBuilder scopeBuilder = new StringBuilder("");
        String prodKeyScope;

        if (scopeKey.equals(APIConstants.OAUTH2_DEFAULT_SCOPE)) {
            scopeBuilder.append("Default  ");
        } else {
            List<String> inputScopeList = new ArrayList<String>(Arrays.asList(scopeKey.split(" ")));
            String scopeName = "";
            for (String inputScope : inputScopeList) {
                for (Scope availableScope : availableScopeSet) {
                    if (availableScope.getKey().equals(inputScope)) {
                        scopeName = availableScope.getName();
                        break;
                    }
                }
                scopeBuilder.append(scopeName);
                scopeBuilder.append(", ");
            }
        }
        prodKeyScope = scopeBuilder.toString();
        prodKeyScope = prodKeyScope.substring(0, prodKeyScope.length() - 2);
        return prodKeyScope;
    }

    private void addAPIObj(SubscribedAPI subscribedAPI, JSONArray apisArray,
                           Application appObject) throws APIManagementException {
        JSONObject apiObj = new JSONObject();
        try {
            API api = getAPIInfo(subscribedAPI.getApiId());
            apiObj.put("name", subscribedAPI.getApiId().getApiName());
            apiObj.put("provider", APIUtil.replaceEmailDomainBack(subscribedAPI.getApiId().getProviderName()));
            apiObj.put("version", subscribedAPI.getApiId().getVersion());
            apiObj.put("status", api.getStatus().toString());
            apiObj.put("tier", subscribedAPI.getTier().getDisplayName());
            apiObj.put("subStatus", subscribedAPI.getSubStatus());
            apiObj.put("thumburl", APIUtil.prependWebContextRoot(api.getThumbnailUrl()));
            apiObj.put("context", api.getContext());
            //Read key from the appObject
            APIKey prodKey = APIUtil.getAppKey(appObject, APIConstants.API_KEY_TYPE_PRODUCTION);
            if (prodKey != null) {
                apiObj.put("prodKey", prodKey.getAccessToken());
                apiObj.put("prodConsumerKey", prodKey.getConsumerKey());
                apiObj.put("prodConsumerSecret", prodKey.getConsumerSecret());
                apiObj.put("prodAuthorizedDomains", prodKey.getAuthorizedDomains());
                if (APIUtil.isApplicationAccessTokenNeverExpire(prodKey.getValidityPeriod())) {
                    apiObj.put("prodValidityTime", -1);
                } else {
                    apiObj.put("prodValidityTime", prodKey.getValidityPeriod());
                }
                //apiObj.put("prodValidityRemainingTime", apiObj, apiMgtDAO.getApplicationAccessTokenRemainingValidityPeriod(prodKey.getAccessToken()));
            } else {
                apiObj.put("prodKey", null);
                apiObj.put("prodConsumerKey", null);
                apiObj.put("prodConsumerSecret", null);
                apiObj.put("prodAuthorizedDomains", null);
                if (APIUtil.isApplicationAccessTokenNeverExpire(APIUtil.getApplicationAccessTokenValidityPeriodInSeconds())) {
                    apiObj.put("prodValidityTime", -1);
                } else {
                    apiObj.put("prodValidityTime", APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() * 1000);
                }
                // apiObj.put("prodValidityRemainingTime", apiObj, getApplicationAccessTokenValidityPeriodInSeconds() * 1000);
            }

            APIKey sandboxKey = APIUtil.getAppKey(appObject, APIConstants.API_KEY_TYPE_SANDBOX);
            if (sandboxKey != null) {
                apiObj.put("sandboxKey", sandboxKey.getAccessToken());
                apiObj.put("sandboxConsumerKey", sandboxKey.getConsumerKey());
                apiObj.put("sandboxConsumerSecret", sandboxKey.getConsumerSecret());
                apiObj.put("sandAuthorizedDomains", sandboxKey.getAuthorizedDomains());
                if (APIUtil.isApplicationAccessTokenNeverExpire(sandboxKey.getValidityPeriod())) {
                    apiObj.put("sandValidityTime", -1);
                } else {
                    apiObj.put("sandValidityTime", sandboxKey.getValidityPeriod());
                }
                //apiObj.put("sandValidityRemainingTime", apiObj, apiMgtDAO.getApplicationAccessTokenRemainingValidityPeriod(sandboxKey.getAccessToken()));
            } else {
                apiObj.put("sandboxKey", null);
                apiObj.put("sandboxConsumerKey", null);
                apiObj.put("sandboxConsumerSecret", null);
                apiObj.put("sandAuthorizedDomains", null);
                if (APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() < 0) {
                    apiObj.put("sandValidityTime", -1);
                } else {
                    apiObj.put("sandValidityTime", APIUtil.getApplicationAccessTokenValidityPeriodInSeconds() * 1000);
                }
                // apiObj.put("sandValidityRemainingTime", apiObj, getApplicationAccessTokenValidityPeriodInSeconds() * 1000);
            }
            apiObj.put("hasMultipleEndpoints", String.valueOf(api.getSandboxUrl() != null));
            apisArray.add(apiObj);
        } catch (APIManagementException e) {
            log.error("Error while obtaining application metadata", e);
        }
    }


    private static APIKey getKey(SubscribedAPI api, String keyType) {
        List<APIKey> apiKeys = api.getKeys();
        return getKeyOfType(apiKeys, keyType);
    }
    
    private static APIKey getKeyOfType(List<APIKey> apiKeys, String keyType) {
        for (APIKey key : apiKeys) {
            if (keyType.equals(key.getType())) {
                return key;
            }
        }
        return null;
    }

    @Override
	public JSONObject getSwaggerResource(String name, String version,
			String provider) throws APIManagementException {
		if (provider != null) {
			provider = APIUtil.replaceEmailDomain(provider);
		}
		provider = (provider != null ? provider.trim() : null);
		name = (name != null ? name.trim() : null);
		version = (version != null ? version.trim() : null);
		
		APIIdentifier apiId = new APIIdentifier(provider, name, version);

		boolean isTenantFlowStarted = false;
		String apiJSON = null;
		try {
			String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil
					.replaceEmailDomainBack(provider));
			if (tenantDomain != null
					&& !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME
							.equals(tenantDomain)) {
				isTenantFlowStarted = true;
				PrivilegedCarbonContext.startTenantFlow();
				PrivilegedCarbonContext.getThreadLocalCarbonContext()
						.setTenantDomain(tenantDomain, true);
			}
			apiJSON = getSwaggerDefinition(apiId);
		} finally {
			if (isTenantFlowStarted) {
				PrivilegedCarbonContext.endTenantFlow();
			}
		}

		JSONObject row = new JSONObject();
		row.put("swagger", apiJSON);
		
		return row;
	}
    
    @Override
    public JSONArray getSubscriptionsByApplication(String applicationName, String userName) throws APIManagementException{
        boolean isTenantFlowStarted = false;
        JSONArray subscriptionArray = new JSONArray();
        try {
            String tenantDomain = MultitenantUtils.getTenantDomain(APIUtil.replaceEmailDomainBack(userName));
            if (tenantDomain != null && !MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equals(tenantDomain)) {
                isTenantFlowStarted = true;
                PrivilegedCarbonContext.startTenantFlow();
                PrivilegedCarbonContext.getThreadLocalCarbonContext().setTenantDomain(tenantDomain, true);
            }
            Subscriber subscriber = new Subscriber(userName);
            Set<SubscribedAPI> subscribedAPIs = getSubscribedAPIs(subscriber, applicationName);

            int i = 0;
            for (SubscribedAPI subscribedAPI : subscribedAPIs) {
                API api = getAPI(subscribedAPI.getApiId());
                JSONObject row = new JSONObject();
                row.put("apiName", subscribedAPI.getApiId().getApiName());
                row.put("apiVersion", subscribedAPI.getApiId().getVersion());
                row.put("apiProvider", APIUtil.replaceEmailDomainBack(subscribedAPI.getApiId().getProviderName()));
                row.put("description", api.getDescription());
                row.put("subscribedTier", subscribedAPI.getTier().getName());
                row.put("status", api.getStatus().getStatus());
                subscriptionArray.add(i, row);
                i++;
            }
        } finally {
            if (isTenantFlowStarted) {
                PrivilegedCarbonContext.endTenantFlow();
            }
        }
    	return subscriptionArray;
    }

    /**
     * This method will return regenerated tokens according to the argument params
     *
     * @param userId,applicationName,requestedScopes,oldAccessToken,accessAllowDomainsArr
     * @param consumerKey,consumerSecret,validityTime
     * @return newAccessTokenJson
     * @throws APIManagementException,AxisFault
     */
    public JSONObject getRefreshToken(String userId, String applicationName, String requestedScopes,
            String oldAccessToken, JSONArray accessAllowDomainsArr, String consumerKey, String consumerSecret,
            String validityTime) throws APIManagementException, AxisFault {

        JSONObject newAccessTokenJson = new JSONObject();
        if (isEmptyDataCheck(userId, applicationName, requestedScopes, oldAccessToken, consumerKey, consumerSecret,
                validityTime)) {

            String[] accessAllowDomainsArray = new String[accessAllowDomainsArr.size()];
            for (int i = 0; i < accessAllowDomainsArr.size(); i++) {
                accessAllowDomainsArray[i] = (String) accessAllowDomainsArr.get(i);
            }

            //Check whether old access token is already available
            if (isApplicationTokenExists(oldAccessToken)) {
                //SubscriberKeyMgtClient keyMgtClient = HostObjectUtils.getKeyManagementClient();
                SubscriberKeyMgtClient keyMgtClient = APIUtil.getKeyManagementClient();
                ApplicationKeysDTO dto = new ApplicationKeysDTO();
                String accessToken;
                String tokenScope;
                try {
                    //Regenerate the application access key
                    accessToken = keyMgtClient
                            .regenerateApplicationAccessKey(requestedScopes, oldAccessToken, accessAllowDomainsArray,
                                    consumerKey, consumerSecret, validityTime);
                    if (accessToken != null) {
                        //Set newly generated application access token
                        dto.setApplicationAccessToken(accessToken);
                    }

                    tokenScope = getScopesByToken(accessToken);
                    Set<Scope> scopeSet = new LinkedHashSet<Scope>();
                    String tokenScopeNames = "";
                    Subscriber subscriber = new Subscriber(userId);
                    //get subscribed APIs set for application
                    Set<SubscribedAPI> subscribedAPIs = getSubscribedAPIs(subscriber, applicationName);
                    List<APIIdentifier> identifiers = new ArrayList<APIIdentifier>();

                    for (SubscribedAPI subscribedAPI : subscribedAPIs) {
                        identifiers.add(subscribedAPI.getApiId());
                    }

                    if (!identifiers.isEmpty()) {
                        //get scopes for subscribed apis
                        scopeSet = getScopesBySubscribedAPIs(identifiers);
                        //convert scope keys to names
                        tokenScopeNames = getScopeNamesbyKey(tokenScope, scopeSet);
                    }

                    newAccessTokenJson.put("accessToken", dto.getApplicationAccessToken());
                    newAccessTokenJson.put("consumerKey", dto.getConsumerKey());
                    newAccessTokenJson.put("consumerSecret", dto.getConsumerSecret());
                    newAccessTokenJson.put("validityTime", validityTime);
                    newAccessTokenJson.put("tokenScope", tokenScopeNames);
                    boolean isRegenerateOptionEnabled = true;
                    if (getApplicationAccessTokenValidityPeriodInSeconds() < 0) {
                        isRegenerateOptionEnabled = false;
                    }
                    newAccessTokenJson.put("enableRegenarate", isRegenerateOptionEnabled);
                } catch (APIManagementException e) {
                    handleException("Error while refreshing the access token.", e);
                } catch (Exception e) {
                    handleException(e.getMessage(), e);
                }
            } else {
                handleException("Cannot regenerate a new access token. There's no access token available as : "
                        + oldAccessToken);
            }
            return newAccessTokenJson;
        } else {
            handleException("Invalid types of input parameters.");
            return null;
        }
    }

    private static long getApplicationAccessTokenValidityPeriodInSeconds() {
        return OAuthServerConfiguration.getInstance().getApplicationAccessTokenValidityPeriodInSeconds();
    }

    private boolean isEmptyDataCheck(String userId, String applicationName, String requestedScopes,
            String oldAccessToken, String consumerKey, String consumerSecret, String validityTime) {
        return (StringUtils.isNotBlank(userId) && StringUtils.isNotBlank(applicationName) && StringUtils
                .isNotBlank(requestedScopes) && StringUtils.isNotBlank(oldAccessToken) && StringUtils
                .isNotBlank(consumerKey) && StringUtils.isNotBlank(consumerSecret) && StringUtils
                .isNotBlank(validityTime));
    }
}
