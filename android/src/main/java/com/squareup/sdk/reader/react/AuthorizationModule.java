/*
Copyright 2022 Square Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.squareup.sdk.reader.react;

import android.os.Handler;
import android.os.Looper;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.squareup.sdk.reader.ReaderSdk;
import com.squareup.sdk.reader.authorization.AuthorizeCallback;
import com.squareup.sdk.reader.authorization.AuthorizeErrorCode;
import com.squareup.sdk.reader.authorization.DeauthorizeCallback;
import com.squareup.sdk.reader.authorization.DeauthorizeErrorCode;
import com.squareup.sdk.reader.authorization.Location;
import com.squareup.sdk.reader.core.CallbackReference;
import com.squareup.sdk.reader.core.Result;
import com.squareup.sdk.reader.core.ResultError;
import com.squareup.sdk.reader.react.internal.ErrorHandlerUtils;
import com.squareup.sdk.reader.react.internal.converter.LocationConverter;
import com.squareup.sdk.reader.react.internal.ReaderSdkException;

class AuthorizationModule extends ReactContextBaseJavaModule {
    // Define all the authorization error debug codes and messages below
    // These error codes and messages **MUST** align with iOS error codes and javascript error codes
    // Search KEEP_IN_SYNC_AUTHORIZE_ERROR to update all places

    // react native module debug error codes
    private static final String RN_AUTH_LOCATION_NOT_AUTHORIZED = "rn_auth_location_not_authorized";

    // react native module debug messages
    private static final String RN_MESSAGE_AUTH_LOCATION_NOT_AUTHORIZED = "This device must be authorized with a Square location in order to get that location. Obtain an authorization code for a Square location from the mobile/authorization-code endpoint and then call authorizeAsync.";

    // Android only react native errors and messages
    private static final String RN_AUTHORIZE_ALREADY_IN_PROGRESS = "rn_authorize_already_in_progress";
    private static final String RN_DEAUTHORIZE_ALREADY_IN_PROGRESS = "rn_deauthorize_already_in_progress";
    private static final String RN_MESSAGE_AUTHORIZE_ALREADY_IN_PROGRESS = "Authorization is already in progress. Please wait for authorizeAsync to complete.";
    private static final String RN_MESSAGE_DEAUTHORIZE_ALREADY_IN_PROGRESS = "Deauthorization is already in progress. Please wait for deauthorizeAsync to complete.";

    private volatile CallbackReference authorizeCallbackRef;
    private volatile CallbackReference deauthorizeCallbackRef;
    private final Handler mainLooperHandler;

    public AuthorizationModule(ReactApplicationContext reactContext) {
        super(reactContext);
        mainLooperHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public String getName() {
        return "RNReaderSDKAuthorization";
    }

    @ReactMethod
    public void isAuthorized(Promise promise) {
        promise.resolve(ReaderSdk.authorizationManager().getAuthorizationState().isAuthorized());
    }

    @ReactMethod
    public void isAuthorizationInProgress(Promise promise) {
        promise.resolve(ReaderSdk.authorizationManager().getAuthorizationState().isAuthorizationInProgress());
    }

    @ReactMethod
    public void authorizedLocation(Promise promise) {
        if (ReaderSdk.authorizationManager().getAuthorizationState().isAuthorized()) {
            LocationConverter locationConverter = new LocationConverter();
            promise.resolve(locationConverter.toJSObject(ReaderSdk.authorizationManager().getAuthorizationState().getAuthorizedLocation()));
        } else {
            String errorJsonMessage = ErrorHandlerUtils.createNativeModuleError(RN_AUTH_LOCATION_NOT_AUTHORIZED, RN_MESSAGE_AUTH_LOCATION_NOT_AUTHORIZED);
            promise.reject(ErrorHandlerUtils.USAGE_ERROR, new ReaderSdkException(errorJsonMessage));
        }
    }

    @ReactMethod
    public void authorize(final String authCode, final Promise promise) {
        if (authorizeCallbackRef != null) {
            String errorJsonMessage = ErrorHandlerUtils.createNativeModuleError(RN_AUTHORIZE_ALREADY_IN_PROGRESS, RN_MESSAGE_AUTHORIZE_ALREADY_IN_PROGRESS);
            promise.reject(ErrorHandlerUtils.USAGE_ERROR, new ReaderSdkException(errorJsonMessage));
            return;
        }
        AuthorizeCallback authCallback = new AuthorizeCallback() {
            @Override
            public void onResult(Result<Location, ResultError<AuthorizeErrorCode>> result) {
                authorizeCallbackRef.clear();
                authorizeCallbackRef = null;
                if (result.isError()) {
                    ResultError<AuthorizeErrorCode> error = result.getError();
                    String errorJsonMessage = ErrorHandlerUtils.serializeErrorToJson(error.getDebugCode(), error.getMessage(), error.getDebugMessage());
                    promise.reject(ErrorHandlerUtils.getErrorCode(error.getCode()), new ReaderSdkException(errorJsonMessage));
                    return;
                }
                Location location = result.getSuccessValue();
                LocationConverter locationConverter = new LocationConverter();
                promise.resolve(locationConverter.toJSObject(location));
            }
        };
        authorizeCallbackRef = ReaderSdk.authorizationManager().addAuthorizeCallback(authCallback);
        mainLooperHandler.post(new Runnable() {
            @Override
            public void run() {
                ReaderSdk.authorizationManager().authorize(authCode);
            }
        });
    }

    @ReactMethod
    public void canDeauthorize(Promise promise) {
        promise.resolve(ReaderSdk.authorizationManager().getAuthorizationState().canDeauthorize());
    }

    @ReactMethod
    public void deauthorize(final Promise promise) {
        if (deauthorizeCallbackRef != null) {
            String errorJsonMessage = ErrorHandlerUtils.createNativeModuleError(RN_DEAUTHORIZE_ALREADY_IN_PROGRESS, RN_MESSAGE_DEAUTHORIZE_ALREADY_IN_PROGRESS);
            promise.reject(ErrorHandlerUtils.USAGE_ERROR, new ReaderSdkException(errorJsonMessage));
            return;
        }
        DeauthorizeCallback deauthCallback = new DeauthorizeCallback() {
            @Override
            public void onResult(Result<Void, ResultError<DeauthorizeErrorCode>> result) {
                deauthorizeCallbackRef.clear();
                deauthorizeCallbackRef = null;
                if (result.isError()) {
                    ResultError<DeauthorizeErrorCode> error = result.getError();
                    String errorJsonMessage = ErrorHandlerUtils.serializeErrorToJson(error.getDebugCode(), error.getMessage(), error.getDebugMessage());
                    promise.reject(ErrorHandlerUtils.getErrorCode(error.getCode()), new ReaderSdkException(errorJsonMessage));
                    return;
                }
                promise.resolve(null);
            }
        };
        deauthorizeCallbackRef = ReaderSdk.authorizationManager().addDeauthorizeCallback(deauthCallback);
        mainLooperHandler.post(new Runnable() {
            @Override
            public void run() {
                ReaderSdk.authorizationManager().deauthorize();
            }
        });
    }

    @Override
    public void onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy();
        // clear the callback to avoid memory leaks when react native module is destroyed
        if (authorizeCallbackRef != null) {
            authorizeCallbackRef.clear();
        }
        if (deauthorizeCallbackRef != null) {
            deauthorizeCallbackRef.clear();
        }
    }
}
