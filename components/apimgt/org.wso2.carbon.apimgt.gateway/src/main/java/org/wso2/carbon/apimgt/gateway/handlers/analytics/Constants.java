/*
 *  Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.wso2.carbon.apimgt.gateway.handlers.analytics;

/**
 * analytics related gateway constants
 */
public class Constants {
    public static final String REQUEST_START_TIME_PROPERTY = "apim.analytics.request.start.time";
    public static final String BACKEND_START_TIME_PROPERTY = "apim.analytics.backend.start.time";
    public static final String BACKEND_LATENCY_PROPERTY = "api.analytics.backend.latency";
    public static final String BACKEND_RESPONSE_CODE = "api.analytics.backend.response_code";
    public static final String USER_AGENT_PROPERTY = "api.analytics.user.agent";
    public static final String CACHED_RESPONSE_KEY = "CachableResponse";

    public static final String REGION_ID = "asia";
    public static final String DEPLOYMENT_ID = "prod";
    public static final String SUCCESS_EVENT_TYPE = "response";
    public static final String FAULTY_EVENT_TYPE = "fault";
    public static final String UNKNOWN_VALUE = "UNKNOWN";

}
