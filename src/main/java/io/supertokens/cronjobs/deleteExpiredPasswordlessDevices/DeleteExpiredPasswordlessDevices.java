/*
 *    Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.cronjobs.deleteExpiredPasswordlessDevices;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.supertokens.Main;
import io.supertokens.ResourceDistributor;
import io.supertokens.config.Config;
import io.supertokens.cronjobs.CronTask;
import io.supertokens.cronjobs.CronTaskTest;
import io.supertokens.pluginInterface.STORAGE_TYPE;
import io.supertokens.pluginInterface.passwordless.PasswordlessCode;
import io.supertokens.pluginInterface.passwordless.PasswordlessDevice;
import io.supertokens.pluginInterface.passwordless.sqlStorage.PasswordlessSQLStorage;
import io.supertokens.storageLayer.StorageLayer;

public class DeleteExpiredPasswordlessDevices extends CronTask {

    public static final String RESOURCE_KEY = "io.supertokens.cronjobs.deleteExpiredPasswordlessDevices"
            + ".DeleteExpiredPasswordlessDevices";

    private DeleteExpiredPasswordlessDevices(Main main) {
        super("DeleteExpiredPasswordlessDevices", main);
    }

    public static DeleteExpiredPasswordlessDevices getInstance(Main main) {
        ResourceDistributor.SingletonResource instance = main.getResourceDistributor().getResource(RESOURCE_KEY);
        if (instance == null) {
            instance = main.getResourceDistributor().setResource(RESOURCE_KEY,
                    new DeleteExpiredPasswordlessDevices(main));
        }
        return (DeleteExpiredPasswordlessDevices) instance;
    }

    @Override
    protected void doTask() throws Exception {
        if (StorageLayer.getStorage(this.main).getType() != STORAGE_TYPE.SQL) {
            return;
        }

        PasswordlessSQLStorage storage = StorageLayer.getPasswordlessStorage(this.main);

        long codeExpirationCutoff = System.currentTimeMillis() - Config.getConfig(main).getPasswordlessCodeLifetime();
        PasswordlessCode[] expiredCodes = storage.getCodesBefore(codeExpirationCutoff);
        Set<String> uniqueDevicesIdHashes = Stream.of(expiredCodes).map(code -> code.deviceIdHash)
                .collect(Collectors.toSet());

        for (String deviceIdHash : uniqueDevicesIdHashes) {
            storage.startTransactionHibernate(session -> {
                PasswordlessDevice device = storage.getDevice_Transaction(session, deviceIdHash);
                if (device == null) {
                    return null;
                }
                PasswordlessCode[] codes = storage.getCodesOfDevice_Transaction(session, deviceIdHash);

                if (Stream.of(codes).allMatch(code -> code.createdAt < codeExpirationCutoff)) {
                    storage.deleteDevice_Transaction(session, deviceIdHash);
                }
                // We don't delete expired codes without the device because we want to detect if the submitted
                // user input code belongs to an expired code or if it's just incorrect.
                storage.commitTransaction(session);
                return null;
            });
        }

    }

    @Override
    public int getIntervalTimeSeconds() {
        if (Main.isTesting) {
            Integer interval = CronTaskTest.getInstance(main).getIntervalInSeconds(RESOURCE_KEY);
            if (interval != null) {
                return interval;
            }
        }
        return 3600; // every hour.
    }

    @Override
    public int getInitialWaitTimeSeconds() {
        if (!Main.isTesting) {
            return getIntervalTimeSeconds();
        } else {
            return 0;
        }
    }
}
