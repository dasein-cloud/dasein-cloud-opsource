/**
 * Copyright (C) 2009-2013 Dell, Inc.
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */

package org.dasein.cloud.opsource.compute;


import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.log4j.Logger;
import org.dasein.cloud.*;
import org.dasein.cloud.compute.*;

import org.dasein.cloud.dc.Region;
import org.dasein.cloud.network.IPVersion;
import org.dasein.cloud.network.RawAddress;
import org.dasein.cloud.opsource.OpSource;
import org.dasein.cloud.opsource.OpSourceMethod;
import org.dasein.cloud.opsource.Param;
import org.dasein.cloud.util.APITrace;
import org.dasein.cloud.util.Cache;
import org.dasein.cloud.util.CacheLevel;
import org.dasein.util.CalendarWrapper;
import org.dasein.util.Jiterator;
import org.dasein.util.JiteratorPopulator;
import org.dasein.util.PopulatorThread;
import org.dasein.util.uom.storage.Gigabyte;
import org.dasein.util.uom.storage.Megabyte;
import org.dasein.util.uom.storage.Storage;
import org.dasein.util.uom.time.Day;
import org.dasein.util.uom.time.TimePeriod;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class VirtualMachines extends AbstractVMSupport<OpSource> {
    static public final Logger logger = OpSource.getLogger(VirtualMachines.class);

    static private final String DESTROY_VIRTUAL_MACHINE = "delete";
    static private final String CLEAN_VIRTUAL_MACHINE = "clean";
    static private final String REBOOT_VIRTUAL_MACHINE = "reboot";
    static private final String START_VIRTUAL_MACHINE = "start";
    static private final String PAUSE_VIRTUAL_MACHINE = "shutdown";
    static private final String HARD_STOP_VIRTUAL_MACHINE = "poweroff";
    static private final String ADD_LOCAL_STORAGE = "addLocalStorage";
    /** Node tag name */
    //static private final String Deployed_Server_Tag = "Server";
    static private final String Pending_Deployed_Server_Tag = "PendingDeployServer";

    long waitTimeToAttempt = 30000L;

    private OpSource provider;

    public VirtualMachines(OpSource provider) {
        super(provider);
        this.provider = provider;
    }

    public boolean attachDisk(String serverId, int sizeInGb) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.attachDisk");
        try {
            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
            Param param = new Param(OpSource.SERVER_BASE_PATH, null);
            parameters.put(0, param);

            param = new Param(serverId, null);
            parameters.put(1, param);

            param = new Param("amount", String.valueOf(sizeInGb));
            parameters.put(2, param);

            OpSourceMethod method = new OpSourceMethod(provider,
                    provider.buildUrl("addLocalStorage",true, parameters),
                    provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET", null));

            Document doc = method.invoke();

            return method.parseRequestResult("Attaching disk", doc , "result","resultDetail");
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void start(@Nonnull String serverId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.start");
        try{
            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();

            Param param = new Param(OpSource.SERVER_BASE_PATH, null);
            parameters.put(0, param);
            param = new Param(serverId, null);
            parameters.put(1, param);

            OpSourceMethod method = new OpSourceMethod(provider,
                    provider.buildUrl(START_VIRTUAL_MACHINE,true, parameters),
                    provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));
            method.parseRequestResult("Booting vm",method.invoke(), "result", "resultDetail");
        }
        finally{
            APITrace.end();
        }
    }

    private boolean cleanFailedVM(String serverId) throws InternalException, CloudException {
        APITrace.begin(provider, "VM.cleanFailedVM");
        try{
            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
            Param param = new Param(OpSource.SERVER_BASE_PATH, null);
            parameters.put(0, param);
            param = new Param(serverId, null);
            parameters.put(1, param);

            OpSourceMethod method = new OpSourceMethod(provider,
                    provider.buildUrl(CLEAN_VIRTUAL_MACHINE,true, parameters),
                    provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));
            return method.parseRequestResult("Clean failed vm",method.invoke(),"result", "resultDetail");
        }finally{
            APITrace.end();
        }
    }


    @Override
    public VirtualMachine alterVirtualMachine(@Nonnull String serverId, @Nonnull VMScalingOptions vmScalingOptions) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.alterVirtualMachine");
        try{
            String[] parts;
            try{
                parts = vmScalingOptions.getProviderProductId().split(":");
            }
            catch(Exception ex){
                throw new CloudException("Invalid product string format. Ensure you are using the format CPU:RAM:[HDD(s)]");
            }

            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
            Param param = new Param(OpSource.SERVER_BASE_PATH, null);
            parameters.put(0, param);
            param = new Param(serverId, null);
            parameters.put(1, param);

            VirtualMachine vm = getVirtualMachine(serverId);
            String currentCpuCount = vm.getProductId().substring(0, vm.getProductId().indexOf(":"));
            String currentRam = vm.getProductId().substring(currentCpuCount.length() + 1, vm.getProductId().lastIndexOf(":"));

            //Ensure only one disk is being added - OpSource only supports a single disksize operation
            if(parts.length >= 3){
                String diskString = parts[2];
                String currentDiskString = vm.getProductId().substring(vm.getProductId().lastIndexOf(":") + 1);
                if(!diskString.equals(currentDiskString)){
                    int currentDiskCount = currentDiskString.split(",").length;
                    int newDiskCount = diskString.split(",").length;
                    if(newDiskCount > currentDiskCount + 1)throw new CloudException("Only one disk can be added in a single scaling operation. Check your product string format.");
                }
            }

            String requestBody = "";
            boolean isCpuChanged = false;
            if(parts.length >= 1){
                try{
                    int newCpuCount = -1;
                    try{
                        newCpuCount = Integer.parseInt(parts[0]);
                    }
                    catch(NumberFormatException ex){}
                    if(newCpuCount != Integer.parseInt(currentCpuCount) && newCpuCount != -1){
                        if(newCpuCount > 0 && newCpuCount <= 8){
                            requestBody = "cpuCount=" + newCpuCount;
                            isCpuChanged = true;
                        }
                        else throw new CloudException("Invalid CPU value. CPU count must be between 1 and 8.");
                    }
                }
                catch(Exception ex){
                    throw new CloudException("Invalid CPU value. Ensure you are using the format CPU:RAM:[HDD(s)]");
                }
            }
            if(parts.length >= 2){
                try{
                    int newMemory = -1;
                    try{
                        newMemory = Integer.parseInt(parts[1]);
                    }
                    catch(NumberFormatException ex){}
                    if(newMemory != Integer.parseInt(currentRam) && newMemory != -1){
                        if(newMemory > 0 && newMemory <= 65536){
                            if(isCpuChanged)requestBody += "&";
                            requestBody += "memory=" + (newMemory);//Required to be in MB
                        }
                        else throw new CloudException("Invalid RAM value. RAM can only go up to 64GB.");
                    }
                }
                catch(Exception ex){
                    throw new CloudException("Invalid RAM value. Ensure you are using the format CPU:RAM:[HDD(s)]");
                }
            }
            boolean success = true;
            if(!requestBody.equals("")){
                OpSourceMethod method = new OpSourceMethod(provider,
                        provider.buildUrl(null, true, parameters),
                        provider.getBasicRequestParameters(OpSource.Content_Type_Value_Modify, "POST", requestBody));
                success =  method.parseRequestResult("Alter vm", method.invoke(), "result", "resultDetail");
            }

            if(success){
                String currentProductId = vm.getProductId();
                if(parts.length >= 3){
                    String currentDiskString = currentProductId.substring(currentProductId.lastIndexOf(":") + 1);
                    if(parts[2].equals(currentDiskString)) return getVirtualMachine(serverId);
                    else{
                        parts[2] = parts[2].replace("[", "");
                        parts[2] = parts[2].replace("]", "");
                        currentDiskString = currentDiskString.replace("[", "");
                        currentDiskString = currentDiskString.replace("]", "");

                        String[] newDisks;
                        String[] currentDisks;
                        if(parts[2].indexOf(",") > 0)newDisks = parts[2].split(",");
                        else newDisks = new String[]{parts[2]};
                        if(currentDiskString.indexOf(",") > 0)currentDisks = currentDiskString.split(",");
                        else currentDisks = new String[]{currentDiskString};

                        if(currentDisks.length > newDisks.length) throw new CloudException("Only scaling up is supported for disk alterations.");
                        else{
                            try{
                                final int newDiskSize = Integer.parseInt(newDisks[newDisks.length-1]);
                                final String fServerId = serverId;
                                Thread t = new Thread(){
                                    public void run(){
                                        provider.hold();
                                        try{
                                            try{
                                                addLocalStorage(fServerId, newDiskSize);
                                            }
                                            catch (Throwable th){
                                                logger.debug("Alter VM failed while adding storage. CPU and RAM alteration may have been sucessful.");
                                            }
                                        }
                                        finally {
                                            provider.release();
                                        }
                                    }
                                };
                                t.setName("Alter OpSource VM: " + vm.getProviderVirtualMachineId());
                                t.setDaemon(true);
                                t.start();
                            }
                            catch(NumberFormatException ex){
                                throw new CloudException("Invalid format for HDD in product description.");
                            }
                        }
                    }
                }
                return getVirtualMachine(serverId);
            }
            else throw new CloudException("The attempt to alter the VM failed for an unknown reason");
        }
        finally {
            APITrace.end();
        }
    }

    private void addLocalStorage(String serverId, int storageSize){
        HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
        Param param = new Param(OpSource.SERVER_BASE_PATH, null);
        parameters.put(0, param);
        param = new Param(serverId, null);
        parameters.put(1, param);

        long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 20L);
        Exception currentException = null;
        while( timeout > System.currentTimeMillis() ) {
            try{
                OpSourceMethod method = new OpSourceMethod(provider,
                        provider.buildUrl(ADD_LOCAL_STORAGE + "&amount=" + storageSize, true, parameters),
                        provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET", null));
                if(method.parseRequestResult("Alter vm - HDD", method.invoke(), "result", "resultDetail")){
                    currentException = null;
                    break;
                }
                else{
                    currentException = new CloudException("Modification failed without explanation");
                }
            }
            catch (Exception ex){
                logger.warn("Modification of local storage failed: " + ex.getMessage());
                currentException = ex;
            }
            try { Thread.sleep(30000L); }
            catch( InterruptedException ignore ) { }
        }
        if( currentException == null ) {
            logger.info("Modification succeeded");
        }
        else {
            logger.error("Server could not be modified: " + currentException.getMessage());
            currentException.printStackTrace();
        }
    }

    @Nullable
    @Override
    public VMScalingCapabilities describeVerticalScalingCapabilities() throws CloudException, InternalException {
        return VMScalingCapabilities.getInstance(false, true, Requirement.OPTIONAL, Requirement.OPTIONAL);
    }

    @Override
    public int getCostFactor(@Nonnull VmState vmState) throws InternalException, CloudException {
        return (vmState.equals(VmState.STOPPED) ? 0 : 100);
    }

    @Override
    public int getMaximumVirtualMachineCount() throws CloudException, InternalException {
        return -2;
    }

    @Override
    public @Nullable VirtualMachineProduct getProduct(@Nonnull String productId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.getProduct");
        try {
            for( Architecture architecture : Architecture.values() ) {
                for( VirtualMachineProduct product : listProducts(architecture) ) {
                    if( product.getProviderProductId().equals(productId) ) {
                        return product;
                    }
                }
            }
            if( logger.isDebugEnabled() ) {
                logger.debug("Unknown product ID for cloud.com: " + productId);
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull String getProviderTermForServer(@Nonnull Locale locale) {
        return "Server";
    }

    @Override
    public VirtualMachine getVirtualMachine(@Nonnull String serverId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.getVirtualMachine");
        try {
            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
            Param param = new Param(OpSource.SERVER_WITH_STATE, null);
            parameters.put(0, param);

            OpSourceMethod method = new OpSourceMethod(provider,
                    provider.buildUrl("id=" + serverId, true, parameters),
                    provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));

            Document doc = method.invoke();

            NodeList  matches = doc.getElementsByTagName("serverWithState");
            if(matches != null){
                return toVirtualMachineWithStatus(matches.item(0), "");
            }
            if( logger.isDebugEnabled() ) {
                logger.debug("Can not identify VM with ID " + serverId);
            }
            return null;
        }
        finally {
            APITrace.end();
        }
    }

    public VirtualMachine getVirtualMachineByNameAndVlan(String name, String providerVlanId) throws InternalException, CloudException {
        if( logger.isDebugEnabled() ) {
            logger.debug("Identify VM with VM Name " + name);
        }

        //ArrayList<VirtualMachine> list = (ArrayList<VirtualMachine>)listVirtualMachines();
        for(VirtualMachine vm : listVirtualMachines(true) ){
            try{
                if(vm != null && vm.getName().equals(name) && vm.getProviderVlanId().equals(providerVlanId)){
                    return vm;
                }
            }
            catch(Exception ex){
                logger.debug(ex.getMessage());
            }
        }

        /*
		ArrayList<VirtualMachine> list = (ArrayList<VirtualMachine>) listPendingServers();
		for(VirtualMachine vm : list ){
			if(vm.getName().equals(name)){
				return vm;
			}
		}
		list = (ArrayList<VirtualMachine>) listDeployedServers();
		for(VirtualMachine vm : list ){
			if(vm.getName().equals(name)){
				return vm;
			}
		}
		*/
        if( logger.isDebugEnabled() ) {
            logger.debug("Can not identify VM with VM Name " + name);
        }
        return null;
    }

    @Nonnull
    @Override
    public Requirement identifyImageRequirement(@Nonnull ImageClass imageClass) throws CloudException, InternalException {
        return (imageClass.equals(ImageClass.MACHINE) ? Requirement.REQUIRED : Requirement.NONE);
    }


    @Override
    public @Nonnull Requirement identifyPasswordRequirement(Platform platform) throws CloudException, InternalException{
        return Requirement.REQUIRED;
    }

    @Override
    public @Nonnull Requirement identifyRootVolumeRequirement() throws CloudException, InternalException {
        return Requirement.NONE;
    }

    @Override
    public @Nonnull Requirement identifyShellKeyRequirement(Platform platform) throws CloudException, InternalException{
        return Requirement.NONE;
    }

    @Nonnull
    @Override
    public Requirement identifyStaticIPRequirement() throws CloudException, InternalException {
        return Requirement.OPTIONAL;
    }

    @Override
    public @Nonnull Requirement identifyVlanRequirement() throws CloudException, InternalException {
        return Requirement.REQUIRED;
    }

    @Override
    public boolean isAPITerminationPreventable() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean isBasicAnalyticsSupported() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean isExtendedAnalyticsSupported() throws CloudException, InternalException {
        return false;
    }

    @Override
    public boolean isSubscribed() throws CloudException, InternalException {
        return true;
    }

    @Override
    public boolean isUserDataSupported() throws CloudException, InternalException {
        return false;
    }

    @Override
    public @Nonnull VirtualMachine launch(final @Nonnull VMLaunchOptions withLaunchOptions) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VM.launch");
        try {
            //VirtualMachineProduct product = getProduct(withLaunchOptions.getStandardProductId());
            String imageId = withLaunchOptions.getMachineImageId();
            String inZoneId = withLaunchOptions.getDataCenterId();
            final String name = withLaunchOptions.getHostName();
            String description = withLaunchOptions.getDescription();
            final String withVlanId = withLaunchOptions.getVlanId();

            /** First step get the target image */
            if( logger.isInfoEnabled() ) {
                logger.info("Fetching deployment information from the target image: " + imageId);
            }
            ServerImage imageSupport = provider.getComputeServices().getImageSupport();
            MachineImage origImage = imageSupport.getOpSourceImage(imageId);

            if(logger.isInfoEnabled()){
                logger.info("Launching vm with product string: " + withLaunchOptions.getStandardProductId());
            }

            String productString = withLaunchOptions.getStandardProductId();
            // product id format cpu:ram
            String cpuCount;
            String ramSize;
            //String volumeSizes;
            String[] productIds = productString.split(":");
            if (productIds.length == 2) {
                cpuCount = productIds[0];
                ramSize = productIds[1];
            }
            else {
                throw new InternalError("Invalid product id string");
            }


            if( origImage == null ) {
                logger.error("No such image to launch VM: " + imageId);
                throw new CloudException("No such image to launch VM: " + imageId);
            }

            final int targetCPU = Integer.parseInt(cpuCount);
            final int targetMemory = Integer.parseInt(ramSize);
            //final int targetDisk = Integer.parseInt(volumeSizes);

            final int currentCPU = (origImage.getTag("cpuCount") == null) ? 0 : Integer.valueOf((String)origImage.getTag("cpuCount"));
            final int currentMemory = (origImage.getTag("memory") == null) ? 0 : Integer.valueOf((String)origImage.getTag("memory"));
            final int currentDisk = 10;

            if( logger.isDebugEnabled() ) {
                //logger.debug("Launch request for " + targetCPU + "/" + targetMemory + "/" + targetDisk + " against " + currentCPU + "/" + currentMemory);
                logger.debug("Launch request for " + targetCPU + "/" + targetMemory + " against " + currentCPU + "/" + currentMemory);
            }

            String password = getRandomPassword();
            if(withLaunchOptions.getBootstrapPassword() != null && !withLaunchOptions.getBootstrapPassword().equals(""))password = withLaunchOptions.getBootstrapPassword();
            //if( targetDisk == 0 && currentCPU == targetCPU && currentMemory == targetMemory ){
            if(currentCPU == targetCPU && currentMemory == targetMemory){
                if( deploy(origImage.getProviderMachineImageId(), inZoneId, name, description, withVlanId, password, "true") ) {
                    VirtualMachine server = getVirtualMachineByNameAndVlan(name, withVlanId);
                    server.setRootPassword(password);
                    return server;
                }
                else {
                    throw new CloudException("Fail to launch the server");
                }

            }
            //else if( targetDisk == 0 && ((targetCPU == 1 && targetMemory == 2048) || (targetCPU == 2 && targetMemory == 4096) || (targetCPU == 4 && targetMemory == 6144))){
            else if((targetCPU == 1 && targetMemory == 2048) || (targetCPU == 2 && targetMemory == 4096) || (targetCPU == 4 && targetMemory == 6144)){
                /**  If it is Opsource OS, then get the target image with the same cpu and memory */
                MachineImage targetImage = imageSupport.searchImage(origImage.getPlatform(), origImage.getArchitecture(), targetCPU, targetMemory);

                if(targetImage != null) {
                    if( deploy(targetImage.getProviderMachineImageId(), inZoneId, name, description, withVlanId, password, "true") ){
                        VirtualMachine server = getVirtualMachineByNameAndVlan(name, withVlanId);
                        server.setRootPassword(password);
                        return server;
                    }
                    else {
                        throw new CloudException("Fail to launch the server");
                    }
                }
            }
            logger.info("Need to modify server after deployment, pursuing a multi-step deployment operation");
            /** There is target image with the CPU and memory required, then need to modify the server after deploying */

            /** Second step deploy VM */

            if( !deploy(imageId, inZoneId, name, description, withVlanId, password, "false") ) {
                throw new CloudException("Fail to deploy VM without further information");
            }

            final VirtualMachine server = getVirtualMachineByNameAndVlan(name, withVlanId);

            /** update the hardware (CPU, memory configuration)*/
            if(server == null){
                throw new CloudException("Server failed to deploy without explaination");
            }
            server.setRootPassword(password);

            Thread t = new Thread() {
                public void run() {
                    provider.hold();
                    try {
                        try {
                            //configure(server, name, currentCPU, currentMemory, currentDisk, targetCPU, targetMemory, targetDisk);
                            configure(server, name, withVlanId, currentCPU, currentMemory, currentDisk, targetCPU, targetMemory);
                        }
                        catch( Throwable t ) {
                            logger.error("Failed to complete configuration of " + server.getProviderVirtualMachineId() + " in OpSource: " + t.getMessage());
                            t.printStackTrace();
                        }
                    }
                    finally {
                        provider.release();
                    }
                }
            };
            t.setName("Configure OpSource VM " + server.getProviderVirtualMachineId());
            t.setDaemon(true);
            t.start();

            return server;
        }
        finally{
            APITrace.end();
        }
    }

    //private void configure(VirtualMachine server, String name, int currentCPU, int currentMemory, int currentDisk, int targetCPU, int targetMemory, int targetDisk) {
    private void configure(VirtualMachine server, String name, String providerVlanId, int currentCPU, int currentMemory, int currentDisk, int targetCPU, int targetMemory) {
        APITrace.begin(getProvider(), "VM.configure");
        try {
            if( logger.isInfoEnabled() ) {
                logger.info("Configuring " + server.getName() + " [#" + server.getProviderVirtualMachineId() + "] - " + server.getCurrentState());
            }
            if( currentCPU != targetCPU || currentMemory != targetMemory ) {
                if( logger.isInfoEnabled() ) {
                    logger.info("Need to reconfigure CPU and/or memory");
                }
                /** Modify server to target cpu and memory */

                /** VM has finished deployment before continuing, therefore wait 15s */
                try {
                    server = getVirtualMachineByNameAndVlan(name, providerVlanId);
                }
                catch( Exception e ) {
                    logger.warn("Unable to load server for configuration: " + e.getMessage());
                }
                if( server == null ) {
                    logger.error("Server disappeared while waiting for deployment to complete");
                    return;
                }
                currentCPU = Integer.valueOf((String) server.getTag("cpuCount"));
                currentMemory = Integer.valueOf((String) server.getTag("memory"));

                if( currentCPU != targetCPU || currentMemory != targetMemory ) {
                    //long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 20L);
                    long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 90L);

                    Exception currentException = null;

                    logger.info("Beginning modification process...");
                    while( timeout > System.currentTimeMillis() ) {
                        try {
                            if( modify(server.getProviderVirtualMachineId(), targetCPU, targetMemory) ) {
                                currentException = null;
                                break;
                            }
                            else {
                                currentException = new CloudException("Modification failed without explanation");
                            }
                        }
                        catch( Exception e ) {
                            logger.warn("Modification of CPU and Memory failed: " + e.getMessage());
                            currentException = e;
                        }
                        try { Thread.sleep(30000L); }
                        catch( InterruptedException ignore ) { }
                    }
                    if( currentException == null ) {
                        logger.info("Modification of CPU and Memory succeeded");
                    }
                    else {
                        logger.error("Server could not be modified: " + currentException.getMessage());
                        currentException.printStackTrace();
                    }
                }
            }
            /** Third Step: attach the disk */
        /* No longer attaching disks on launch
            if( targetDisk != currentDisk) {
                if( logger.isInfoEnabled() ) {
                    logger.info("Need to reconfigure for disk: " + currentDisk + " vs " + targetDisk);
                }
                long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 20L);
                Exception currentException = null;

                // Update usually take another 6 mins
                while( System.currentTimeMillis() < timeout ) {
                    try {
                        server = getVirtualMachineByName(name);
                        if( server == null ) {
                            logger.error("Lost access to server while attempting to attach disk");
                            return;
                        }
                    }
                    catch( Exception e ) {
                        logger.warn("Unable to load the server's current state, praying the old one works: " + e.getMessage());
                    }

                    if( server.getProductId() != null ) {
                        try {
                            VirtualMachineProduct prd = getProduct(server.getProductId());

                            if( prd != null && prd.getRootVolumeSize().intValue() == targetDisk ) {
                                if( logger.isInfoEnabled() ) {
                                    logger.info("Target match, aborting attachment for " + server.getProviderVirtualMachineId());
                                }
                                break;
                            }
                            if( attachDisk(server.getProviderVirtualMachineId(), targetDisk) ){
                                if( logger.isInfoEnabled() ) {
                                    logger.info("Attach succeeded for " + server.getProviderVirtualMachineId());
                                }
                                break;
                            }
                        }
                        catch( Exception e ) {
                            logger.warn("Error during attach: " + e.getMessage());
                            currentException = e;
                        }
                    }
                    try { Thread.sleep(30000L); }
                    catch( InterruptedException ignore ) { }
                }
                if( currentException != null ) {
                    logger.error("Unable to attach disk: " + currentException.getMessage());
                    currentException.printStackTrace();
                }
            }
            */
            /**  Fourth Step: boot the server */
            /** Update usually take another 10 mins, wait 5 minutes first */
            long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 15L);

            if( logger.isInfoEnabled() ) {
                logger.info("Booting " + server.getProviderVirtualMachineId());
            }
            while( System.currentTimeMillis() < timeout ) {
                try {
                    /** Begin to start the VM */
                    server = getVirtualMachineByNameAndVlan(name, providerVlanId);
                    if( server == null ) {
                        logger.error("Server disappeared while performing bootup");
                        return;
                    }
                    if( server.getCurrentState().equals(VmState.RUNNING)) {
                        if( logger.isInfoEnabled() ) {
                            logger.info(server.getProviderVirtualMachineId() + " is now RUNNING");
                        }
                        return;
                    }
                    else if( server.getCurrentState().equals(VmState.STOPPED) ) {
                        start(server.getProviderVirtualMachineId());
                    }
                }
                catch( Exception e ) {
                    logger.warn("Error during boot process, maybe retry?: " + e.getMessage());
                }
                try { Thread.sleep(15000L); }
                catch( InterruptedException ignore ) { }
            }
        }
        finally {
            APITrace.end();
        }
    }

    private boolean deploy(@Nonnull String imageId, String inZoneId, String name, String description, String withVlanId, String adminPassword, String isStart) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.deploy");
        try {
            inZoneId = translateZone(inZoneId);
            /** Create post body */
            Document doc = provider.createDoc();
            Element server = doc.createElementNS("http://oec.api.opsource.net/schemas/server", "Server");

            Element nameElmt = doc.createElement("name");
            nameElmt.setTextContent(name);

            Element descriptionElmt = doc.createElement("description");
            descriptionElmt.setTextContent(description);

            if(withVlanId == null){
                withVlanId = provider.getDefaultVlanId();
            }

            Element vlanResourcePath = doc.createElement("vlanResourcePath");
            vlanResourcePath.setTextContent(provider.getVlanResourcePathFromVlanId(withVlanId, provider.getContext().getRegionId()));

            Element imageResourcePath = doc.createElement("imageResourcePath");
            imageResourcePath.setTextContent(provider.getImageResourcePathFromImaged(imageId));

            if(adminPassword == null){
                adminPassword = getRandomPassword();
            }
            else{
                if(adminPassword.length() < 8){
                    throw new InternalException("Password require a minimum of 8 characters!!!");
                }
            }

            Element administratorPassword = doc.createElement("administratorPassword");
            administratorPassword.setTextContent(adminPassword);

            Element isStartElmt = doc.createElement("isStarted");

            isStartElmt.setTextContent(isStart);

            server.appendChild(nameElmt);
            server.appendChild(descriptionElmt);
            server.appendChild(vlanResourcePath);
            server.appendChild(imageResourcePath);
            server.appendChild(administratorPassword);

            server.appendChild(isStartElmt);
            doc.appendChild(server);

            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();

            Param param = new Param(OpSource.SERVER_BASE_PATH, null);
            parameters.put(0, param);

            OpSourceMethod method = new OpSourceMethod(provider,
                    provider.buildUrl(null,true, parameters),
                    provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "POST", provider.convertDomToString(doc)));
            return method.parseRequestResult("Deploying server",method.invoke(), "result", "resultDetail");
        }
        finally {
            APITrace.end();
        }
    }


    @Override
    public @Nonnull Iterable<String> listFirewalls(@Nonnull String vmId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.listFirewalls");
        try {
            /** Firewall Id is the same as the network ID*/
            VirtualMachine vm = this.getVirtualMachine(vmId);

            if(vm == null){
                return Collections.emptyList();
            }
            String networkId = vm.getProviderVlanId();
            if(networkId != null){
                ArrayList<String> list = new ArrayList<String>();
                list.add(networkId);
                return list;
            }
            return Collections.emptyList();
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public @Nonnull Iterable<VirtualMachineProduct> listProducts(@Nonnull Architecture architecture) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.listProducts");
        try {
            Cache<VirtualMachineProduct> cache = Cache.getInstance(provider, "vmProduct" + architecture.name(), VirtualMachineProduct.class, CacheLevel.REGION_ACCOUNT, new TimePeriod<Day>(1, TimePeriod.DAY));
            Iterable<VirtualMachineProduct> cached = cache.get(getContext());

            if( cached != null ) {
                return cached;
            }
            List<VirtualMachineProduct> products = new ArrayList<VirtualMachineProduct>();

            VirtualMachineProduct product;
            /** OpSource enables any combination of CPU (1 -8 for East 1-4 or west) and RAM (1 - 64G for East and 1-32G for west) */

            //int maxCPUNum = 0, maxMemInGB =0,  diskSizeInGB = 0, maxMemInMB = 0;
            int maxCPUNum = 0, maxMemInGB =0,  maxMemInMB = 0;

            /** Obtain the maximum CPU and Memory for each data center */
            String regionId = provider.getDefaultRegionId();
            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
            Param param = new Param(OpSource.LOCATION_BASE_PATH, null);
            parameters.put(0, param);

            OpSourceMethod method = new OpSourceMethod(provider,
                    provider.buildUrl(null,true, parameters),
                    provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));

            Document doc = method.invoke();

            String sNS = "";
            try{
                sNS = doc.getDocumentElement().getTagName().substring(0, doc.getDocumentElement().getTagName().indexOf(":") + 1);
            }
            catch(IndexOutOfBoundsException ignore){
                // ignore
            }
            NodeList blocks = doc.getElementsByTagName(sNS + "datacenterWithLimits");

            if(blocks != null){
                for(int i=0; i< blocks.getLength();i++){
                    Node item = blocks.item(i);

                    RegionComputingPower r = toRegionComputingPower(item, sNS);
                    if( r.getProviderRegionId().equals(regionId)){
                        maxCPUNum = r.getMaxCPUNum();
                        maxMemInMB = r.getMaxMemInMB();
                    }
                }
            }

            for( int disk = 0 ; disk < 6; disk ++ ){
                //diskSizeInGB = disk * 50;

                for(int cpuNum =1;cpuNum <= maxCPUNum;cpuNum ++){
                    /**
                     * Default cpuNum = 1, 2, max ram = 8
                     * cpuNum = 3, 4, min ram 4, max ram = 32
                     * cpuNum = 1, 2, max ram = 8
                     */
                    int ramInMB = 1024*cpuNum;
                    if(cpuNum <=2){
                        ramInMB = 1024;
                    }
                    while((ramInMB/1024) <= 4*cpuNum && ramInMB <=  maxMemInMB){
                        product = new VirtualMachineProduct();
                        //product.setProviderProductId(cpuNum + ":" + ramInMB + ":" + diskSizeInGB);
                        product.setProviderProductId(cpuNum + ":" + ramInMB);
                        //product.setName(" (" + cpuNum + " CPU/" + ramInMB + " MB RAM/" + diskSizeInGB + " GB Disk)");
                        product.setName(" (" + cpuNum + " CPU/" + ramInMB + " MB RAM)");
                        //product.setDescription(" (" + cpuNum + " CPU/" + ramInMB + " MB RAM/" + diskSizeInGB + " GB Disk)");
                        product.setDescription(" (" + cpuNum + " CPU/" + ramInMB + " MB RAM)");
                        product.setRamSize(new Storage<Megabyte>(ramInMB, Storage.MEGABYTE));
                        product.setCpuCount(cpuNum);
                        product.setRootVolumeSize(new Storage<Gigabyte>(10, Storage.GIGABYTE));
                        products.add(product);

                        if(cpuNum <=2){
                            ramInMB = ramInMB + 1024;
                        }else{
                            ramInMB = ramInMB + ramInMB;
                        }
                    }
                }
            }
            cache.put(getContext(), products);
            return products;
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public Iterable<Architecture> listSupportedArchitectures() throws InternalException, CloudException {
        ArrayList<Architecture> list = new ArrayList<Architecture>();
        list.add(Architecture.I64);
        list.add(Architecture.I32);
        return list;
    }

    @Nonnull
    @Override
    public Iterable<ResourceStatus> listVirtualMachineStatus() throws InternalException, CloudException {
        ArrayList<ResourceStatus> list = new ArrayList<ResourceStatus>();
        for(VirtualMachine vm : listVirtualMachines()){
            ResourceStatus status = new ResourceStatus(vm.getProviderVirtualMachineId(), vm.getCurrentState());
            list.add(status);
        }
        return list;
    }

    public @Nonnull Iterable<VirtualMachine> listVirtualMachines(final boolean withOrdering) throws InternalException, CloudException {
        PopulatorThread<VirtualMachine> populator = new PopulatorThread<VirtualMachine>(new JiteratorPopulator<VirtualMachine>() {
            @Override
            public void populate(@Nonnull Jiterator<VirtualMachine> iterator) throws Exception {
                HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
                Param param = new Param(OpSource.SERVER_WITH_STATE, null);
                parameters.put(0, param);

                listPage(iterator, 1, 250, parameters, withOrdering);
            }
        });

        populator.populate();
        return populator.getResult();
    }

    @Override
    public @Nonnull Iterable<VirtualMachine> listVirtualMachines() throws InternalException, CloudException {
        PopulatorThread<VirtualMachine> populator = new PopulatorThread<VirtualMachine>(new JiteratorPopulator<VirtualMachine>() {
            @Override
            public void populate(@Nonnull Jiterator<VirtualMachine> iterator) throws Exception {
                HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
                Param param = new Param(OpSource.SERVER_WITH_STATE, null);
                parameters.put(0, param);

                listPage(iterator, 1, 250, parameters, false);
            }
        });

        populator.populate();
        return populator.getResult();
    }

    private void listPage(final Jiterator<VirtualMachine> iterator, final int pageNumber, final int pageSize, final HashMap<Integer,Param> parameters, final boolean withOrdering) throws CloudException, InternalException {
        boolean completeList = false;
        String sortAndOrder = "";
        if(withOrdering){
            sortAndOrder = "&orderBy=created.desc&state=PENDING_ADD&state=NORMAL&state=PENDING_CHANGE";
        }

        OpSourceMethod method = new OpSourceMethod(provider,
                provider.buildUrl("pageSize=" + pageSize + "&pageNumber=" + pageNumber + "&location=" + provider.getContext().getRegionId() + sortAndOrder, true, parameters),
                provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET", null));

        Document doc = method.invoke();
        NodeList headMatches = doc.getElementsByTagName("ServersWithState");
        Collection<VirtualMachine> next = null;

        if(headMatches != null){

            int currentPageSize = Integer.parseInt(headMatches.item(0).getAttributes().getNamedItem("pageCount").getNodeValue());

            if( currentPageSize >= pageSize ) {
                PopulatorThread<VirtualMachine> populator = new PopulatorThread<VirtualMachine>(new JiteratorPopulator<VirtualMachine>() {
                    @Override
                    public void populate(@Nonnull Jiterator<VirtualMachine> ignored) throws Exception {
                        listPage(iterator, pageNumber+1, pageSize, parameters, withOrdering);
                    }
                });

                populator.populate();
                next = populator.getResult();
            }
        }
        NodeList  matches = doc.getElementsByTagName("serverWithState");

        if(matches != null){
            for(int i=0;i<matches.getLength();i++){
                VirtualMachine vm = toVirtualMachineWithStatus(matches.item(i), "");
                if(vm != null) {
                    iterator.push(vm);
                }
            }
        }
        if( next != null ) {
            // don't return from this method until ALL results from the next page are done
            Iterator<VirtualMachine> it = next.iterator();

            while( it.hasNext() ) {
                it.next();
            }
            // we're done, it's done -> done, done
        }
    }

    /*
    @Override
    public @Nonnull Iterable<VirtualMachine> listVirtualMachines() throws InternalException, CloudException {
        ArrayList<VirtualMachine> vms = new ArrayList<VirtualMachine>();
        HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
        Param param = new Param(OpSource.SERVER_WITH_STATE, null);
        parameters.put(0, param);

        int pageSize = 250;
        int currentPage = 1;
        return doListVirtualMachines(vms, parameters, pageSize, currentPage);
    }

    private ArrayList<VirtualMachine> doListVirtualMachines(ArrayList<VirtualMachine> vms, HashMap<Integer, Param> parameters, int pageSize, int currentPage)throws InternalException, CloudException{
        boolean completeList = false;

        OpSourceMethod method = new OpSourceMethod(provider,
                provider.buildUrl("pageSize=" + pageSize + "&pageNumber=" + currentPage + "&location=" + provider.getContext().getRegionId(), true, parameters),
                provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET", null));

        Document doc = method.invoke();
        NodeList headMatches = doc.getElementsByTagName("ServersWithState");
        if(headMatches != null){
            int currentPageSize = Integer.parseInt(headMatches.item(0).getAttributes().getNamedItem("pageCount").getNodeValue());
            if(currentPageSize < pageSize)completeList = true;
        }

        NodeList  matches = doc.getElementsByTagName("serverWithState");
        if(matches != null){
            for(int i=0;i<matches.getLength();i++){
                VirtualMachine vm = toVirtualMachineWithStatus(matches.item(i), "");
                System.out.println("VM Platform: " + vm.getPlatform());
                if(vm != null)vms.add(vm);
            }
        }
        currentPage++;
        if(!completeList) doListVirtualMachines(vms, parameters, pageSize, currentPage);
        return vms;
    }
    */

    @Override
    public void pause(@Nonnull String vmId) throws InternalException, CloudException {
        throw new OperationNotSupportedException("Pause/unpause is not supported");
    }

    @Deprecated
    private Iterable<VirtualMachine> listDeployedServers() throws InternalException, CloudException {
        ArrayList<VirtualMachine> list = new ArrayList<VirtualMachine>();

        /** Get deployed Server */
        HashMap<Integer, Param> parameters = new HashMap<Integer, Param>();
        Param param = new Param(OpSource.SERVER_BASE_PATH, null);
        parameters.put(0, param);
        param = new Param("deployed", null);
        parameters.put(1, param);

        OpSourceMethod method = new OpSourceMethod(provider,
                provider.buildUrl(null, true, parameters),
                provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));

        Document doc = method.invoke();

        NodeList matches = doc.getElementsByTagName("DeployedServer");

        if(matches != null){
            for( int i=0; i<matches.getLength(); i++ ) {
                Node node = matches.item(i);
                VirtualMachine vm = this.toVirtualMachine(node, false, "");
                if( vm != null ) {
                    list.add(vm);
                }
            }
        }
        return list;
    }

    @Deprecated
    private Iterable<VirtualMachine> listPendingServers() throws InternalException, CloudException {
        ArrayList<VirtualMachine> list = new ArrayList<VirtualMachine>();

        /** Get pending deploy server */
        HashMap<Integer, Param> parameters = new HashMap<Integer, Param>();
        Param param = new Param(OpSource.SERVER_BASE_PATH, null);
        parameters.put(0, param);
        param = new Param("pendingDeploy", null);
        parameters.put(1, param);

        OpSourceMethod method = new OpSourceMethod(provider,
                provider.buildUrl(null, true, parameters),
                provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));

        Document doc = method.invoke();

        NodeList matches = doc.getElementsByTagName(Pending_Deployed_Server_Tag);
        if(matches != null){
            for( int i=0; i<matches.getLength(); i++ ) {
                Node node = matches.item(i);
                VirtualMachine vm = this.toVirtualMachine(node, true, "");
                if( vm != null ) {
                    list.add(vm);
                }
            }
        }
        return list;
    }

	/** Modify VM with the cpu and memory */
	private boolean modify(String serverId, int cpuCount, int memoryInMb ) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.modify");
        try{
            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
            Param param = new Param(OpSource.SERVER_BASE_PATH, null);
            parameters.put(0, param);
            param = new Param(serverId, null);
            parameters.put(1, param);

            /** Create post body */
            String requestBody = "cpuCount=";
            requestBody += cpuCount;
            requestBody += "&memory=" + memoryInMb;

            OpSourceMethod method = new OpSourceMethod(provider,
                    provider.buildUrl(null,true, parameters),
                    provider.getBasicRequestParameters(OpSource.Content_Type_Value_Modify, "POST", requestBody));
            return method.parseRequestResultNoError("Modify vm",method.invoke(), "result", "resultDetail");
        }
        finally {
            APITrace.end();
        }
	}

    @Override
    public void stop(@Nonnull String serverId, boolean hardOff) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.stop");
        try {
            if( !hardOff ) {
                HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
                Param param = new Param(OpSource.SERVER_BASE_PATH, null);
                parameters.put(0, param);
                param = new Param(serverId, null);
                parameters.put(1, param);

                /** Gracefully power off */
                OpSourceMethod method = new OpSourceMethod(provider,
                        provider.buildUrl(PAUSE_VIRTUAL_MACHINE,true, parameters),
                        provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));
                method.parseRequestResult("Pausing vm",method.invoke(),"result","resultDetail");
            }
            else{
                HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
                Param param = new Param(OpSource.SERVER_BASE_PATH, null);
                parameters.put(0, param);
                param = new Param(serverId, null);
                parameters.put(1, param);

                OpSourceMethod method = new OpSourceMethod(provider,
                        provider.buildUrl(HARD_STOP_VIRTUAL_MACHINE,true, parameters),
                        provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET", null));
                method.parseRequestResult("Stopping vm",method.invoke(),"result","resultDetail");
            }
        }
        finally {
            APITrace.end();
        }
    }


    @Override
    public void reboot(@Nonnull String serverId) throws CloudException, InternalException {
        APITrace.begin(getProvider(), "VM.reboot");
        try {
            HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
            Param param = new Param(OpSource.SERVER_BASE_PATH, null);
            parameters.put(0, param);
            param = new Param(serverId, null);
            parameters.put(1, param);

            OpSourceMethod method = new OpSourceMethod(provider,
                    provider.buildUrl(REBOOT_VIRTUAL_MACHINE,true, parameters),
                    provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));
            method.parseRequestResult("Rebooting vm",method.invoke(),"result","resultDetail");
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public boolean supportsPauseUnpause(@Nonnull VirtualMachine vm) {
        return false;
    }

    @Override
    public boolean supportsStartStop(@Nonnull VirtualMachine vm) {
        return true;
    }

    @Override
    public boolean supportsSuspendResume(@Nonnull VirtualMachine vm) {
        return false;
    }

    @Override
    public void terminate(@Nonnull String serverId) throws InternalException, CloudException {
        APITrace.begin(getProvider(), "VM.terminate");
        try {
            if( logger.isInfoEnabled() ) {
                logger.info("Beginning termination process for server " + serverId);
            }
            VirtualMachine server = getVirtualMachine(serverId);

            if( logger.isInfoEnabled() ) {
                logger.info("Current state for " + serverId + ": " + (server == null ? "TERMINATED" : server.getCurrentState()));
            }
            if( server == null ) {
                return;
            }

            /** Release public IP first */
            if( logger.isInfoEnabled() ) {
                logger.info("Releasing public IP prior to termination...");
            }
            if( server.getPublicIpAddresses() != null ) {
                for(String addressId : server.getPublicIpAddresses()){
                    provider.getNetworkServices().getIpAddressSupport().releaseFromServer(addressId);
                }
            }

            /** Now Stop the vm */
            if( logger.isInfoEnabled() ) {
                logger.info("Stopping the server " + serverId + " prior to termination...");
            }
            long timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 20L);

            while( System.currentTimeMillis() < timeout ) {
                try {
                    /** If it is pending, means it is in deployment process, need around 6 mins */
                    server = getVirtualMachine(serverId);

                    if( server == null ) {
                        /** VM already killed */
                        return;
                    }
                    if( server.getCurrentState().equals(VmState.STOPPED) ) {
                        break;
                    }
                    if( server.getCurrentState().equals(VmState.RUNNING) ){
                        stop(serverId);
                        break;
                    }

                }
                catch( Throwable t ) {
                    logger.warn("Error stopping VM: " + t.getMessage());
                }
                try { Thread.sleep(30000L); }
                catch( InterruptedException ignore ) { }
            }
            if( logger.isInfoEnabled() ) {
                logger.info("Waiting for server " + serverId + " to be STOPPED...");
            }
            timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 10L);
            while( System.currentTimeMillis() < timeout ) {
                try {
                    server = getVirtualMachine(serverId);
                    if( server == null ) {
                        return;
                    }
                    if( server.getCurrentState().equals(VmState.TERMINATED) ) {
                        return;
                    }
                    if( server.getCurrentState().equals(VmState.STOPPED) ) {
                        break;
                    }
                }
                catch( Throwable t ) {
                    logger.warn("Error stopping VM: " + t.getMessage());
                }
                try { Thread.sleep(30000L); }
                catch( InterruptedException ignore ) { }
            }
            if( logger.isInfoEnabled() ) {
                logger.info("Finally terminating " + serverId + " now that it is STOPPED");
            }
            timeout = System.currentTimeMillis() + (CalendarWrapper.MINUTE * 10L);
            while( System.currentTimeMillis() < timeout ) {
                try {
                    String  resultCode = killVM(serverId);

                    if( logger.isDebugEnabled() ) {
                        logger.debug("Server " + serverId + " termination result: " + resultCode);
                    }
                    if( resultCode.equals("REASON_0") ){
                        break;
                    }
                    else if( resultCode.equals("REASON_395") ){
                        logger.error(resultCode + ": Could not find VM " + serverId);
                        throw new CloudException(resultCode + ": Could not find VM " + serverId);
                    }
                    else if(resultCode.equals("REASON_100")){
                        logger.error(resultCode + ": Illegal access");
                        throw new CloudException(resultCode + ": Illegal access");
                    }
                    else if(resultCode.equals("REASON_393")){
                        logger.error("The server with " + serverId + " is associated with a Real-Server in load balancer");
                        throw new CloudException("The server with " + serverId + " is associated with a Real-Server in load balancer");
                    }
                    else {
                        try {
                            Thread.sleep(waitTimeToAttempt);
                            logger.info("Cleaning failed deployment for " + serverId);
                            cleanFailedVM(serverId);
                        }
                        catch( Throwable ignore ) {
                            // ignore
                        }
                    }
                }
                catch( CloudException e ) {
                    logger.warn("Failed termination attempt: " + e.getMessage());
                    try{
                        Thread.sleep(waitTimeToAttempt);
                        logger.info("Cleaning failed deployment for " + serverId);
                        cleanFailedVM(serverId);
                    }
                    catch( Throwable ignore ) {
                        // ignore
                    }
                }
            }
            if( logger.isInfoEnabled() ) {
                logger.info("Waiting for " + serverId + " to be TERMINATED...");
            }
            while( System.currentTimeMillis() < timeout ) {
                VirtualMachine vm = getVirtualMachine(serverId);

                if( vm == null || VmState.TERMINATED.equals(vm.getCurrentState()) ) {
                    if( logger.isInfoEnabled() ) {
                        logger.info("VM " + serverId + " successfully TERMINATED");
                    }
                    return;
                }
                try { Thread.sleep(30000L); }
                catch( InterruptedException ignore ) { }
            }
            logger.warn("System timed out waiting for " + serverId + " to complete termination");
        }
        finally {
            APITrace.end();
        }
    }

    @Override
    public void terminate(@Nonnull String instanceId, @Nullable String explanation) throws InternalException, CloudException {
        terminate(instanceId);
    }

    @Override
    public void unpause(@Nonnull String vmId) throws CloudException, InternalException {
        throw new OperationNotSupportedException("Pause/unpause is not supported");
    }

    @Override
    public void updateTags(@Nonnull String VmId, @Nonnull Tag... tags) throws CloudException, InternalException {
        //TODO: Implement for 2013.01
    }

    private String killVM(String serverId) throws InternalException, CloudException {
        HashMap<Integer, Param>  parameters = new HashMap<Integer, Param>();
        Param param = new Param(OpSource.SERVER_BASE_PATH, null);
        parameters.put(0, param);
        param = new Param(serverId, null);
        parameters.put(1, param);

        OpSourceMethod method = new OpSourceMethod(provider,
                provider.buildUrl(DESTROY_VIRTUAL_MACHINE,true, parameters),
                provider.getBasicRequestParameters(OpSource.Content_Type_Value_Single_Para, "GET",null));
        return method.requestResultCode("Terminating vm",method.invoke(),"resultCode");
    }

    private String translateZone(String zoneId) throws InternalException, CloudException {
        if( zoneId == null ) {
            for( Region r : provider.getDataCenterServices().listRegions() ) {
                zoneId = r.getProviderRegionId();
                break;
            }
        }
		/*if(zoneId.endsWith("a")){
			zoneId = zoneId.substring(0, zoneId.length()-1);       	
		}*/
        return zoneId;
    }

    private VirtualMachineProduct getProduct(Architecture architecture, int cpuCout, int memoryInSize, int diskInGB) throws InternalException, CloudException{

        for( VirtualMachineProduct product : listProducts(architecture) ) {
            if( product.getCpuCount() == cpuCout && product.getRamSize().intValue() == memoryInSize  && diskInGB == product.getRootVolumeSize().intValue() ) {
                return product;
            }
        }
        return null;
    }

    private VirtualMachine toVirtualMachineWithStatus(Node node, String nameSpace) throws InternalException, CloudException{
        if(node == null) {
            return null;
        }
        HashMap<String,String> properties = new HashMap<String,String>();
        VirtualMachine server = new VirtualMachine();
        NodeList attributes = node.getChildNodes();


        server.setTags(properties);
        server.setProviderOwnerId(provider.getContext().getAccountNumber());
        server.setClonable(false);
        server.setPausable(false);
        server.setPersistent(true);

        server.setProviderVirtualMachineId(node.getAttributes().getNamedItem("id").getFirstChild().getNodeValue().trim());
        server.setProviderRegionId(node.getAttributes().getNamedItem("location").getFirstChild().getNodeValue().trim());

        boolean isDeployed = false;
        boolean pendingChange = false;
        String serverState = "";
        String failureReason = "";
        //ArrayList<Integer> attachedDisks = new ArrayList<Integer>();
        HashMap<String, String> attachedDisks = new HashMap<String, String>();

        for(int i=0; i<attributes.getLength(); i++){
            Node attribute = attributes.item(i);
            if(attribute.getNodeType() == Node.TEXT_NODE) continue;
            String name = attribute.getNodeName();
            String value = "";
            try{
                value = attribute.getFirstChild().getNodeValue();
            }
            catch(Exception ex){}

            String nameSpaceString = "";
            if(!nameSpace.equals("")) nameSpaceString = nameSpace + ":";

            if(name.equalsIgnoreCase(nameSpaceString + "name")){
                server.setName(value);
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "description")){
                server.setDescription(value);
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "networkId")){
                //if(!provider.isVlanInRegion(value)){ //This code always returns null?
                //    return null;
                //}
                server.setProviderVlanId(value);
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "operatingSystem")){
                String osDisplayName;
                Node osNode = attribute.getAttributes().getNamedItem("displayName").getFirstChild();
                if(osNode != null){
                    osDisplayName = osNode.getNodeValue().trim();
                    if(osDisplayName != null && osDisplayName.contains("64")){
                        server.setArchitecture(Architecture.I64);
                    }
                    else if(osDisplayName != null && osDisplayName.contains("32")){
                        server.setArchitecture(Architecture.I32);
                    }
                    if(osDisplayName != null) {
                        if(osDisplayName.contains("WIN")){
                            server.setPlatform(Platform.WINDOWS);
                        }
                        else server.setPlatform(Platform.guess(osDisplayName));
                    }
                }
                else{
                    server.setPlatform(Platform.UNKNOWN);
                }
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "cpuCount")){
                server.getTags().put("cpuCount", value);
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "memoryMb")){
                server.getTags().put("memory", value);
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "disk")){
                int scsiId = -1;
                Node scsiNode = attribute.getAttributes().getNamedItem("scsiId").getFirstChild();
                if(scsiNode != null){
                    scsiId = Integer.parseInt(scsiNode.getNodeValue());

                    Node sizeNode = attribute.getAttributes().getNamedItem("sizeGb").getFirstChild();
                    if(sizeNode != null){
                        String diskSize = sizeNode.getNodeValue().trim();
                        if(scsiId == 0){
                            server.setTag("osStorage", diskSize);
                            attachedDisks.put(0+"", diskSize);
                            //attachedDisks.add(0, diskSize);
                        }
                        else{
                            server.setTag("additionalLocalStorage" + scsiId, diskSize);
                            attachedDisks.put(scsiId+"", diskSize);
                            //attachedDisks.add(diskSize);
                        }
                    }
                }
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "sourceImageId")){
                server.setProviderMachineImageId(value);
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "privateIp")){
                server.setPrivateAddresses(new RawAddress(value, IPVersion.IPV4));
                server.setProviderAssignedIpAddressId(value);
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "publicIp")){
                server.setPublicAddresses(new RawAddress(value, IPVersion.IPV4));
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "created")){
                DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                try {
                    if(value.contains(".")){
                        String newvalue = value.substring(0,value.indexOf("."))+"Z";
                        server.setCreationTimestamp(df.parse(newvalue).getTime());
                    }else{
                        server.setCreationTimestamp(df.parse(value).getTime());
                    }
                }
                catch( ParseException e ) {
                    logger.warn("Invalid date: " + value);
                    server.setLastBootTimestamp(0L);
                }
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "isDeployed")){
                if(value.equalsIgnoreCase("true"))isDeployed = true;
                else isDeployed = false;
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "isStarted")){
                if(value.equalsIgnoreCase("true"))server.setCurrentState(VmState.RUNNING);
                else server.setCurrentState(VmState.STOPPED);
            }
            else if(name.equalsIgnoreCase(nameSpaceString + "state")){
                serverState = value.trim();
                if(isDeployed && value.equals("PENDING_CHANGE"))pendingChange = true;
                else if(!isDeployed && value.equals("PENDING_ADD"))server.setCurrentState(VmState.PENDING);
            }
            else if(pendingChange && name.equalsIgnoreCase(nameSpaceString + "status")){
                NodeList status = attribute.getChildNodes();
                if(status != null){
                    for(int j=0;j<status.getLength();j++){
                        Node statusNode = status.item(j);

                        if(statusNode.getNodeName().equalsIgnoreCase(nameSpaceString + "action")){
                            String action = statusNode.getFirstChild().getNodeValue().trim();
                            if(action.equalsIgnoreCase("START_SERVER")){
                                server.setCurrentState(VmState.RUNNING);
                                server.setLastBootTimestamp(System.currentTimeMillis());
                            }
                            else if(action.equalsIgnoreCase("POWER_OFF_SERVER"))server.setCurrentState(VmState.STOPPING);
                            else if(action.equalsIgnoreCase("SHUTDOWN_SERVER"))server.setCurrentState(VmState.STOPPING);
                            else if(action.equalsIgnoreCase("RESET_SERVER")){
                                server.setCurrentState(VmState.REBOOTING);
                                server.setLastBootTimestamp(System.currentTimeMillis());
                            }
                            else server.setCurrentState(VmState.PENDING);
                        }
                    }
                }
            }
            else if(!serverState.equals("NORMAL") && !serverState.equals("PENDING_ADD") && !serverState.equals("PENDING_CHANGE") && !serverState.equals("PENDING_DELETE") && name.equalsIgnoreCase(nameSpaceString + "status")){
                //Any other state is in error
                server.setCurrentState(VmState.SUSPENDED);
                NodeList status = attribute.getChildNodes();
                if(status != null){
                    for(int j=0;j<status.getLength();j++){
                        Node statusNode = status.item(j);
                        if(statusNode.getNodeName().equalsIgnoreCase(nameSpaceString + "failureReason")){
                            failureReason = statusNode.getFirstChild().getNodeValue().trim();
                        }
                    }
                }
            }
        }
        if( server.getName() == null ) {
            server.setName(server.getProviderVirtualMachineId());
        }
        if( server.getDescription() == null ) {
            server.setDescription(server.getName());
        }
        if( server.getProviderDataCenterId() == null ) {
            server.setProviderDataCenterId(provider.getDataCenterId(server.getProviderRegionId()));
        }

        if(server.getTag("cpuCount") != null && server.getTag("memory") != null ){
            int cpuCount = Integer.valueOf((String) server.getTag("cpuCount"));
            int memoryInMb = Integer.valueOf((String) server.getTag("memory"));
            String diskString = "[";
            for(int i=0;i<attachedDisks.size();i++){
                String diskSize = attachedDisks.get(i+"");
                diskString += diskSize + ",";
            }
            diskString = diskString.substring(0, diskString.length()-1) + "]";

            /*VirtualMachineProduct product = new VirtualMachineProduct();
            product.setName(cpuCout + " CPU/" + memoryInMb + "MB RAM/" + diskInGb + "GB HD");
            product.setProviderProductId(cpuCout + ":" + memoryInMb + ":" + diskString);
            product.setRamSize(new Storage<Megabyte>((memoryInMb), Storage.MEGABYTE));
            product.setRootVolumeSize(new Storage<Gigabyte>(diskInGb, Storage.GIGABYTE));
            product.setCpuCount(cpuCout);
            product.setDescription(cpuCout + " CPU/" + memoryInMb + "MB RAM/" + diskInGb + "GB HD");*/

            server.setProductId(cpuCount + ":" + memoryInMb + ":" + diskString);
        }
        if(server.getCurrentState().equals(VmState.SUSPENDED) && !failureReason.equals("")){
            server.setTag("serverState", serverState);
            server.setTag("failureReason", failureReason);
        }
        return server;
    }

    @Deprecated
    private VirtualMachine toVirtualMachine(Node node, Boolean isPending, String nameSpace) throws CloudException, InternalException {
        if( node == null ) {
            return null;
        }
        HashMap<String,String> properties = new HashMap<String,String>();
        VirtualMachine server = new VirtualMachine();
        NodeList attributes = node.getChildNodes();

        Architecture bestArchitectureGuess = Architecture.I64;

        server.setTags(properties);

        if(isPending){
            server.setCurrentState(VmState.PENDING);
            server.setImagable(false);
        }else{
            server.setCurrentState(VmState.RUNNING);
            server.setImagable(true);
        }
        server.setProviderOwnerId(provider.getContext().getAccountNumber());
        server.setClonable(false);
        server.setPausable(true);
        server.setPersistent(true);

        server.setProviderRegionId(provider.getContext().getRegionId());

        for( int i=0; i<attributes.getLength(); i++ ) {
            Node attribute = attributes.item(i);
            if(attribute.getNodeType() == Node.TEXT_NODE) continue;
            String name = attribute.getNodeName();
            String value;

            if( attribute.getChildNodes().getLength() > 0 ) {
                value = attribute.getFirstChild().getNodeValue();
            }
            else {
                continue;
            }
            /** Specific server node information */


            String nameSpaceString = "";
            if(!nameSpace.equals("")) nameSpaceString = nameSpace + ":";
            if( name.equals(nameSpaceString + "id") || name.equals("id") ) {
                server.setProviderVirtualMachineId(value);
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "name") ) {
                server.setName(value);
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "description") ) {
                server.setDescription(value);
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "vlanResourcePath") ) {
                String vlanId = provider.getVlanIdFromVlanResourcePath(value);
                if(!provider.isVlanInRegion(vlanId)){
                    return null;
                }
                server.setProviderVlanId(vlanId);
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "operatingSystem") ) {
                NodeList osAttributes  = attribute.getChildNodes();
                for(int j=0;j<osAttributes.getLength();j++ ){
                    Node os = osAttributes.item(j);
                    String osName = os.getNodeName();
                    String osValue ;
                    if( osName.equals(nameSpaceString + "displayName") && os.getChildNodes().getLength() > 0 ) {
                        osValue = os.getFirstChild().getNodeValue();
                    }else{
                        osValue = null ;
                    }

                    if( osValue != null && osValue.contains("64") ) {
                        bestArchitectureGuess = Architecture.I64;
                    }
                    else if( osValue != null && osValue.contains("32") ) {
                        bestArchitectureGuess = Architecture.I32;
                    }
                    if( osValue != null ) {
                        server.setPlatform(Platform.guess(osValue));
                        break;
                    }
                }
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "cpuCount") ) {
                server.getTags().put("cpuCount", value);
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "memory") ) {
                server.getTags().put("memory", value);
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "osStorage") ) {
                server.getTags().put("osStorage", value);
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "additionalLocalStorage") ) {
                server.getTags().put("additionalLocalStorage", value);
            }
            else if(name.equals(nameSpaceString + "machineName") ) {
                //equal to private ip address
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "privateIPAddress") ) {
                if( value != null ) {
                    server.setPrivateIpAddresses(new String[] { value });
                    server.setProviderAssignedIpAddressId(value);
                }
            }
            //DeployedServer
            else if( name.equalsIgnoreCase(nameSpaceString + "publicIpAddress") ) {
                server.setPublicIpAddresses(new String[] { value });
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "isDeployed") ) {
                if(value.equalsIgnoreCase("false")){
                    server.setCurrentState(VmState.PENDING);
                    isPending = true;
                }else{
                    isPending = false;
                }
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "isStarted") ) {
                if( value.equalsIgnoreCase("false") ){
                    server.setCurrentState(VmState.STOPPED);
                }
            }
            else if( name.equalsIgnoreCase(nameSpaceString + "created") ) {
                DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                /** 2012-05-08T02:23:16.999Z */
                try {
                    if(value.contains(".")){
                        String newvalue = value.substring(0,value.indexOf("."))+"Z";
                        server.setCreationTimestamp(df.parse(newvalue).getTime());
                    }else{
                        server.setCreationTimestamp(df.parse(value).getTime());
                    }
                }
                catch( ParseException e ) {
                    logger.warn("Invalid date: " + value);
                    server.setLastBootTimestamp(0L);
                }
            }
            //From here is the deployed server, or pending server
            else if(name.equalsIgnoreCase(nameSpaceString + "machineSpecification") ) {
                NodeList machineAttributes  = attribute.getChildNodes();
                for(int j=0;j<machineAttributes.getLength();j++ ){
                    Node machine = machineAttributes.item(j);
                    if(machine.getNodeType() == Node.TEXT_NODE) continue;

                    if(machine.getNodeName().equalsIgnoreCase(nameSpaceString + "operatingSystem") ){
                        NodeList osAttributes  = machine.getChildNodes();
                        for(int k=0;k<osAttributes.getLength();k++ ){
                            Node os = osAttributes.item(k);

                            if(os.getNodeType() == Node.TEXT_NODE) continue;
                            String osName = os.getNodeName();
                            String osValue = null ;

                            if(osName.equalsIgnoreCase(nameSpaceString + "displayName") && os.hasChildNodes()) {
                                osValue = os.getFirstChild().getNodeValue();
                                Platform platform = Platform.guess(osValue);
                                if(platform.equals(Platform.UNKNOWN)){
                                    platform = Platform.UNIX;
                                }
                                server.setPlatform(platform);

                                if(osValue != null && osValue.contains("64") ) {
                                    bestArchitectureGuess = Architecture.I64;
                                }
                                else if(osValue != null && osValue.contains("32") ) {
                                    bestArchitectureGuess = Architecture.I32;
                                }
                            }
                        }
                    }else if( machine.getNodeName().equalsIgnoreCase(nameSpaceString + "cpuCount") && machine.getFirstChild().getNodeValue() != null ) {
                        server.getTags().put("cpuCount", machine.getFirstChild().getNodeValue());
                    }
                    /** memoryMb pendingDeploy deployed */
                    else if( (machine.getNodeName().equalsIgnoreCase(nameSpaceString + "memory") || machine.getNodeName().equalsIgnoreCase(nameSpaceString + "memoryMb"))&& machine.getFirstChild().getNodeValue() != null ) {
                        server.getTags().put("memory", machine.getFirstChild().getNodeValue());
                    }
                    /** deployedserver osStorageGb */
                    else if( (machine.getNodeName().equalsIgnoreCase(nameSpaceString + "osStorage") ||machine.getNodeName().equalsIgnoreCase(nameSpaceString + "osStorageGb"))&& machine.getFirstChild().getNodeValue() != null) {
                        server.getTags().put("osStorage", machine.getFirstChild().getNodeValue());
                    }
                    /** additionalLocalStorageGb pendingDeploy */
                    else if((machine.getNodeName().equalsIgnoreCase(nameSpaceString + "additionalLocalStorage") || machine.getNodeName().equalsIgnoreCase(nameSpaceString + "additionalLocalStorageGb") ) && machine.getFirstChild().getNodeValue() != null ) {
                        server.getTags().put("additionalLocalStorage", machine.getFirstChild().getNodeValue());
                    }
                }
            }
            /** pendingDeploy or Deployed */
            else if( name.equalsIgnoreCase(nameSpaceString + "sourceImageId") ) {
                server.setProviderMachineImageId(value);
            }
            /** pendingDeploy or Deployed */
            else if( name.equalsIgnoreCase(nameSpaceString + "networkId") ) {
                server.setProviderVlanId(value);
                if(!provider.isVlanInRegion(value)){
                    return null;
                }
            }
            /** From here is the specification for pending deployed server */
            else if( name.equalsIgnoreCase(nameSpaceString + "status") ) {
                NodeList statusAttributes  = attribute.getChildNodes();
                for(int j=0;j<statusAttributes.getLength();j++ ){
                    Node status = statusAttributes.item(j);
                    if(status.getNodeType() == Node.TEXT_NODE) continue;
                    if( status.getNodeName().equalsIgnoreCase(nameSpaceString + "step") ){
                        /** If it is this status means it is pending */
                        server.setCurrentState(VmState.PENDING);
                    }
                    else if( status.getNodeName().equalsIgnoreCase(nameSpaceString + "requestTime") && status.getFirstChild().getNodeValue() != null ) {
                        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ"); //2009-02-03T05:26:32.612278

                        try {
                            if(value.contains(".")){
                                String newvalue = value.substring(0,status.getFirstChild().getNodeValue().indexOf("."))+"Z";
                                server.setCreationTimestamp(df.parse(newvalue).getTime());
                            }else{
                                server.setCreationTimestamp(df.parse(status.getFirstChild().getNodeValue()).getTime());
                            }
                        }
                        catch( ParseException e ) {
                            logger.warn("Invalid date: " + value);
                            server.setLastBootTimestamp(0L);
                        }
                    }
                    else if( status.getNodeName().equalsIgnoreCase(nameSpaceString + "userName") && status.getFirstChild().getNodeValue() != null ) {
                        //This seems to break the cloud syncing operation - removed for now.
                        //server.setProviderOwnerId(status.getFirstChild().getNodeValue());
                    }
                    else if( status.getNodeName().equalsIgnoreCase(nameSpaceString + "numberOfSteps") ) {

                    }
                    else if( status.getNodeName().equalsIgnoreCase(nameSpaceString + "action") ) {
                        String action = status.getFirstChild().getNodeValue();
                        if(action.equalsIgnoreCase("CLEAN_SERVER")){
                            /** Means failed deployed */
                            server.setCurrentState(VmState.PENDING);
                        }
                    }
                }
            }
        }
        if( isPending ) {
            server.setCurrentState(VmState.PENDING);
        }
        if( server.getName() == null ) {
            server.setName(server.getProviderVirtualMachineId());
        }
        if( server.getDescription() == null ) {
            server.setDescription(server.getName());
        }

        if( server.getProviderDataCenterId() == null ) {
            server.setProviderDataCenterId(provider.getDataCenterId(server.getProviderRegionId()));
        }

        if( server.getArchitecture() == null ) {
            server.setArchitecture(bestArchitectureGuess);
        }

        VirtualMachineProduct product = null;

        if(server.getTag("cpuCount") != null && server.getTag("memory") != null ){
            int cpuCout = Integer.valueOf((String) server.getTag("cpuCount"));
            int memoryInMb = Integer.valueOf((String) server.getTag("memory"));
            int diskInGb = 1;

            if(server.getTag("additionalLocalStorage") == null){
                product = getProduct(bestArchitectureGuess, cpuCout, (memoryInMb/1024), 0);
            }
            else{
                diskInGb = Integer.valueOf((String) server.getTag("additionalLocalStorage"));
                product = getProduct(bestArchitectureGuess, cpuCout, (memoryInMb/1024), diskInGb);
            }
            if( product == null ) {
                product = new VirtualMachineProduct();
                product.setName(cpuCout + " CPU/" + memoryInMb + "MB RAM/" + diskInGb + "GB HD");
                product.setProviderProductId(cpuCout + ":" + memoryInMb + ":" + diskInGb);
                product.setRamSize(new Storage<Megabyte>((memoryInMb), Storage.MEGABYTE));
                product.setRootVolumeSize(new Storage<Gigabyte>(diskInGb, Storage.GIGABYTE));
                product.setCpuCount(cpuCout);
                product.setDescription(cpuCout + " CPU/" + memoryInMb + "MB RAM/" + diskInGb + "GB HD");
            }
        }
        if( product == null ) {
            product = new VirtualMachineProduct();
            product.setName("Unknown");
            product.setProviderProductId("unknown");
            product.setRamSize(new Storage<Megabyte>(1024, Storage.MEGABYTE));
            product.setRootVolumeSize(new Storage<Gigabyte>(1, Storage.GIGABYTE));
            product.setCpuCount(1);
            product.setDescription("Unknown product");
        }
        /**  Set public address */
        /**        String[] privateIps = server.getPrivateIpAddresses();

         if(privateIps != null){
         IpAddressImplement ipAddressSupport = new IpAddressImplement(provider);
         String[] publicIps = new String[privateIps.length];
         for(int i= 0; i< privateIps.length; i++){
         NatRule rule = ipAddressSupport.getNatRule(privateIps[i], server.getProviderVlanId());
         if(rule != null){
         publicIps[i] = rule.getNatIp();
         }
         }
         server.setPublicIpAddresses(publicIps);
         }*/

        server.setProductId(product.getProviderProductId());
        return server;
    }

    private RegionComputingPower toRegionComputingPower(Node node, String nameSpace){

        if(node == null){
            return null;
        }

        NodeList data;

        data = node.getChildNodes();

        RegionComputingPower r = new RegionComputingPower();
        for( int i=0; i<data.getLength(); i++ ) {
            Node item = data.item(i);


            if(item.getNodeType() == Node.TEXT_NODE) continue;

            if( item.getNodeName().equals(nameSpace + "location") ) {
                r.setProviderRegionId(item.getFirstChild().getNodeValue());
            }
            else if( item.getNodeName().equals(nameSpace + "displayName") ) {
                r.setName(item.getFirstChild().getNodeValue());
            }
            else if( item.getNodeName().equals(nameSpace + "maxCpu") ) {
                r.setMaxCPUNum(Integer.valueOf(item.getFirstChild().getNodeValue()));
            }
            else if( item.getNodeName().equals(nameSpace + "maxRamMb") ) {
                r.setMaxMemInMB(Integer.valueOf(item.getFirstChild().getNodeValue()));
            }
        }
        return r;
    }

    static private final Random random = new Random();
    static public String alphabet = "ABCEFGHJKMNPRSUVWXYZabcdefghjkmnpqrstuvwxyz0123456789#@()=+/{}[],.?;':|-_!$%^&*~`";
    public String getRandomPassword() {
        StringBuilder password = new StringBuilder();
        int rnd = random.nextInt();
        int length = 17;

        if( rnd < 0 ) {
            rnd = -rnd;
        }
        length = length + (rnd%8);
        while( password.length() < length ) {
            char c;

            rnd = random.nextInt();
            if( rnd < 0 ) {
                rnd = -rnd;
            }
            c = (char)(rnd%255);
            if( alphabet.contains(String.valueOf(c)) ) {
                password.append(c);
            }
        }
        return password.toString();
    }


    @SuppressWarnings("serial")
    public class RegionComputingPower extends Region{

        public int maxCPUNum;
        public int maxMemInMB;

        public int getMaxMemInMB(){
            return maxMemInMB;
        }

        public int getMaxCPUNum(){
            return maxCPUNum;
        }

        public void setMaxMemInMB(int maxMemInMB){
            this.maxMemInMB = maxMemInMB;
        }

        public void setMaxCPUNum(int maxCPUNum){
            this.maxCPUNum = maxCPUNum;
        }
    }
}
