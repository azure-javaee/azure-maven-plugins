/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.appservice.deploy;

import com.azure.resourcemanager.appservice.models.WebAppBase;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.appservice.file.AppServiceKuduClient;
import com.microsoft.azure.toolkit.lib.appservice.file.AzureFunctionsAdminClient;
import com.microsoft.azure.toolkit.lib.appservice.function.AzureFunctions;
import com.microsoft.azure.toolkit.lib.appservice.function.FunctionAppBase;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.annotation.Nonnull;
import java.io.File;
import java.time.Duration;
import java.util.Objects;

public class FlexFunctionDeployHandler implements IFunctionDeployHandler {

    public static final String FAILED_TO_DEPLOY = "Failed to deploy to Azure Function (%s) : ";
    public static final int STATUS_DELAY = 2;
    public static final int STATUS_REPEAT_TIMES = 15;
    public static final String INVALID_STATUS = "Deployment was successful but the app appears to be unhealthy. Please check the app logs.";

    @Override
    @Deprecated
    public void deploy(@Nonnull File file, @Nonnull WebAppBase webAppBase) {
        deploy(file, (FunctionAppBase<?, ?, ?>) Objects.requireNonNull(Azure.az(AzureFunctions.class).getById(webAppBase.id())));
    }

    @Override
    public void deploy(@Nonnull File file, @Nonnull final FunctionAppBase<?,?,?> functionAppBase) {
        final AppServiceKuduClient kuduManager = functionAppBase.getKuduManager();
        try {
            Objects.requireNonNull(kuduManager).flexZipDeploy(file);
            checkFlexAppAfterDeployment(functionAppBase);
        } catch (final Exception e) {
            throw new AzureToolkitRuntimeException(String.format(FAILED_TO_DEPLOY, ExceptionUtils.getRootCauseMessage(e)), e);
        }
        AzureMessager.getMessager().info(String.format(DEPLOY_FINISH, functionAppBase.getHostName()));
    }

    private void checkFlexAppAfterDeployment(@Nonnull final FunctionAppBase<?,?,?> functionAppBase) throws InterruptedException {
        final AzureFunctionsAdminClient adminClient = functionAppBase.getAdminClient();
        if (Objects.isNull(adminClient)){
            return;
        }
        AzureMessager.getMessager().info("Waiting for sync triggers, it may take some moments...");
        Thread.sleep(60 * 1000);
        AzureMessager.getMessager().info("Checking the health of the function app...");
        final Boolean result = Mono.fromCallable(adminClient::getHostStatus)
            .delayElement(Duration.ofSeconds(STATUS_DELAY))
            .subscribeOn(Schedulers.boundedElastic())
            .repeat(STATUS_REPEAT_TIMES)
            .takeUntil(BooleanUtils::isTrue)
            .blockLast();
        if (BooleanUtils.isNotTrue(result)) {
            throw new AzureToolkitRuntimeException(INVALID_STATUS);
        }
    }
}
