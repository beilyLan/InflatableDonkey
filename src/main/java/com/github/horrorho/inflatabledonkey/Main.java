/* 
 * The MIT License
 *
 * Copyright 2015 Ahseya.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.github.horrorho.inflatabledonkey;

import com.dd.plist.NSDictionary;
import com.github.horrorho.inflatabledonkey.args.ArgsParser;
import com.github.horrorho.inflatabledonkey.args.AuthenticationMapper;
import com.github.horrorho.inflatabledonkey.args.Help;
import com.github.horrorho.inflatabledonkey.args.OptionsFactory;
import com.github.horrorho.inflatabledonkey.args.Property;
import com.github.horrorho.inflatabledonkey.data.AccountInfo;
import com.github.horrorho.inflatabledonkey.data.Auth;
import com.github.horrorho.inflatabledonkey.data.Authenticator;
import com.github.horrorho.inflatabledonkey.data.CKInit;
import com.github.horrorho.inflatabledonkey.data.Tokens;
import com.github.horrorho.inflatabledonkey.protocol.ChunkServer;
import com.github.horrorho.inflatabledonkey.protocol.CloudKit;
import com.github.horrorho.inflatabledonkey.protocol.ProtoBufArray;
import com.github.horrorho.inflatabledonkey.requests.AccountSettingsRequestFactory;
import com.github.horrorho.inflatabledonkey.requests.AuthorizeGetRequestFactory;
import com.github.horrorho.inflatabledonkey.requests.CkAppInitBackupRequestFactory;
import com.github.horrorho.inflatabledonkey.requests.Headers;
import com.github.horrorho.inflatabledonkey.requests.M201RequestFactory;
import com.github.horrorho.inflatabledonkey.requests.M211RequestFactory;
import com.github.horrorho.inflatabledonkey.requests.MappedHeaders;
import com.github.horrorho.inflatabledonkey.requests.ProtoBufsRequestFactory;
import com.github.horrorho.inflatabledonkey.responsehandler.InputStreamResponseHandler;
import com.github.horrorho.inflatabledonkey.responsehandler.JsonResponseHandler;
import com.github.horrorho.inflatabledonkey.responsehandler.PropertyListResponseHandler;
import com.github.horrorho.inflatabledonkey.util.PLists;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * iOS9 backup retrieval proof of concept - work in progress. Development tool intended to demonstrate a functional
 * chain of client/ server responses required for backup retrieval, as opposed to being a fully functional backup tool
 * in itself.
 * <p>
 * Postulated steps:
 * <p>
 * 1. Authentication. Functional.
 * <p>
 * 2. Account settings. Functional.
 * <p>
 * 3. CloudKit Application Initialization. Functional.
 * <p>
 * 4. Record zones. Functional.
 * <p>
 * 5. Backup list. Functional.
 * <p>
 * 6. Snapshot list. Functional.
 * <p>
 * 7. Manifest list. Functional.
 * <p>
 * 8. Retrieve assets. Unknown -> now functional.
 * <p>
 * 9. Asset tokens. Unknown.
 * <p>
 * 10.AuthorizeGet. Known if unchanged from iOS8.
 * <p>
 * 11. ChunkServer.FileGroups retrieval. Known if unchanged from iOS8.
 * <p>
 * 12. Assemble assets/ files. Known if unchanged from iOS8.
 * <p>
 * 13. Decrypt files. Known if unchanged from iOS8.
 * <p>
 *
 * Thank you to:
 * <p>
 * <a href="https://github.com/ItsASmallWorld">ItsASmallWorld</a> for deciphering key client/ server interactions and
 * assisting with Protobuf definitions.
 *
 *
 * @author Ahseya
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * @param args the command line arguments
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        // Command line arguments.
        AuthenticationMapper authenticationMapper = AuthenticationMapper.instance();
        OptionsFactory optionsFactory = OptionsFactory.create();
        ArgsParser<Property> argsParser = new ArgsParser<>(optionsFactory);
        Map<Property, String> arguments = new HashMap<>();

        try {
            List<String> operands = argsParser.apply(args, arguments);

            if (arguments.containsKey(Property.ARGS_HELP)) {
                Help.instance().accept(optionsFactory.get().keySet());
                System.exit(0);
            }

            arguments.putAll(authenticationMapper.apply(operands));

        } catch (IllegalArgumentException ex) {
            System.out.println("Error: " + ex.getMessage());
            System.out.println("Try '" + Property.APP_NAME.defaultValue() + " --help' for more information.");
            System.exit(0);
        }

        logger.debug("-- main() - arguments: {}", arguments);

        // Session constants        
        String deviceID = new BigInteger(256, ThreadLocalRandom.current()).toString(16).toUpperCase(Locale.US);
        //deviceID = "51378363AF15278F992AEB76026C539217D49B2F678DB559F33B53528FAD291C";
        String container = "com.apple.backup.ios";
        String bundle = "com.apple.backupd";
        String ckdFetchRecordZonesOperation = "CKDFetchRecordZonesOperation";

        Headers coreHeaders = new MappedHeaders(
                new BasicHeader(Headers.userAgent, "CloudKit/479 (13A404)"),
                new BasicHeader(Headers.xMmeClientInfo, "<iPhone5,3> <iPhone OS;9.0.1;13A404> <com.apple.cloudkit.CloudKitDaemon/479 (com.apple.cloudd/479)>"),
                new BasicHeader(Headers.xCloudKitProtocolVersion, "client=1;comments=1;device=1;presence=1;records=1;sharing=1;subscriptions=1;users=1;mescal=1;"),
                new BasicHeader(Headers.xAppleMmcsProtoVersion, "4.0")
        );

        CloudKit.String mbksync = CloudKit.String.newBuilder()
                .setValue("mbksync")
                .setEncoding(6)
                .build();

        CloudKit.String defaultZone = CloudKit.String.newBuilder()
                .setValue("_defaultZone")
                .setEncoding(6)
                .build();

        CloudKit.String backupAccount = CloudKit.String.newBuilder()
                .setValue("BackupAccount")
                .setEncoding(1)
                .build();

        CloudKit.String seven = CloudKit.String.newBuilder()
                .setValue(UUID.randomUUID().toString()) // Unknown session static UUID.
                .setEncoding(2)
                .build();

        CloudKit.Info info = CloudKit.Info.newBuilder()
                .setContainer(container)
                .setBundle(bundle)
                .setF7(seven)
                .setOs("9.0.1")
                .setApp("com.apple.cloudkit.CloudKitDaemon")
                .setAppVersion("479")
                .setLimit1(40000)
                .setLimit2(40000)
                .setVersion("4.0")
                .setF19(1)
                .setDeviceName("My iPhone")
                .setDeviceID(deviceID)
                .setF23(1)
                .setF25(1)
                .build();

        // Protobuf array response handler.
        InputStreamResponseHandler<List<CloudKit.Response>> ckResponseHandler
                = new InputStreamResponseHandler<>(inputStream -> ProtoBufArray.decode(inputStream, CloudKit.Response.PARSER));

        // Default HttpClient.
        HttpClient httpClient = HttpClients.createDefault();

        /*        
         STEP 1. Authenticate via appleId/ password or dsPrsID:mmeAuthToken.
        
         No different to iOS8.
         */
        logger.info("-- main() - STEP 1. Authenticate via appleId/ password or dsPrsID:mmeAuthToken.");
        Auth auth = arguments.containsKey(Property.AUTHENTICATION_TOKEN)
                ? new Auth(arguments.get(Property.AUTHENTICATION_TOKEN))
                : new Authenticator(coreHeaders).authenticate(
                        httpClient,
                        arguments.get(Property.AUTHENTICATION_APPLEID),
                        arguments.get(Property.AUTHENTICATION_PASSWORD));

        logger.debug("-- main() - auth: {}", auth);
        logger.info("-- main() - dsPrsID:mmeAuthToken: {}:{}", auth.dsPrsID(), auth.mmeAuthToken());

        if (arguments.containsKey(Property.ARGS_TOKEN)) {
            System.out.println("DsPrsID:mmeAuthToken " + auth.dsPrsID() + ":" + auth.mmeAuthToken());
            System.exit(0);
        }

        /*
         STEP 2. Account settings.
                
         New url/ headers otherwise comparable to iOS8.     
         https://setup.icloud.com/setup/get_account_settings

         Returns an account settings plist, that along with numerous other items includes a cloudKitToken.        
         */
        logger.info("-- main() - STEP 2. Account settings.");
        HttpUriRequest accountSettingsRequest = new AccountSettingsRequestFactory(coreHeaders)
                .apply(auth.dsPrsID(), auth.mmeAuthToken());

        NSDictionary settings
                = httpClient.execute(accountSettingsRequest, PropertyListResponseHandler.nsDictionaryResponseHandler());
        logger.debug("-- main() - account settings: {}", settings.toASCIIPropertyList());

        AccountInfo accountInfo = new AccountInfo(PLists.get(settings, "appleAccountInfo"));
        Tokens tokens = new Tokens(PLists.get(settings, "tokens"));

        /* 
         STEP 3. CloudKit Application Initialization.
         
         Url/ headers are specific to the particular bundle/ container required 
         (in our case bundle = com.apple.backupd  container = com.apple.backup.ios)
         cloudKitToken also required
         https://setup.icloud.com/setup/ck/v1/ckAppInit?container=$CONTAINER
        
         Returns a JSON response outlining various urls and a cloudKitUserId.        
         */
        logger.info("-- main() - STEP 3. CloudKit Application Initialization.");
        HttpUriRequest ckAppInitRequest
                = CkAppInitBackupRequestFactory.create()
                .newRequest(accountInfo.dsPrsID(), tokens.mmeAuthToken(), tokens.cloudKitToken());

        JsonResponseHandler<CKInit> jsonResponseHandler = new JsonResponseHandler<>(CKInit.class);

        CKInit ckInit = httpClient.execute(ckAppInitRequest, jsonResponseHandler);
        logger.debug("-- main() - ckInit: {}", ckInit);

        CloudKit.String ckUserId = CloudKit.String.newBuilder()
                .setValue(ckInit.cloudKitUserId())
                .setEncoding(7)
                .build();

        /* 
         STEP 4. Record zones.
        
         Url ckDatabase from ckInit + /record/retrieve
         ~ pXX-ckdatabase.icloud.com:443//api/client/record/retrieve
         Headers similar to step 3
        
         Message type 201 request (cloud_kit.proto)      
         This proto is encoded with Apple's protobuf array encoding (see ProtoBufArray class).
         Possibility of passing multiple requests not yet explored.
        
         Returns record zone data.
        
         */
        logger.info("-- main() - STEP 4. Record zones.");
        CloudKit.Request requestA = M201RequestFactory.instance()
                .newRequest(
                        container,
                        bundle,
                        ckdFetchRecordZonesOperation,
                        UUID.randomUUID().toString(),
                        mbksync,
                        ckUserId,
                        info);
        logger.debug("-- main() - record zones request: {}", requestA);

        HttpUriRequest postA = ProtoBufsRequestFactory.defaultInstance().newRequest(
                ckInit.production().url() + "/record/retrieve",
                container,
                bundle,
                ckInit.cloudKitUserId(),
                tokens.cloudKitToken(),
                UUID.randomUUID().toString(),
                Arrays.asList(requestA),
                coreHeaders);

        List<CloudKit.Response> responseA = httpClient.execute(postA, ckResponseHandler);
        logger.debug("-- main() - record zones response: {}", responseA);

        /* 
         STEP 5. Backup list.
        
         Url/ headers as step 4.
         Message type 211 request, protobuf array encoded.

         Returns device data/ backups.
        
         */
        logger.info("-- main() - STEP 5. Backup list.");
        CloudKit.Request requestB = M211RequestFactory.instance()
                .newRequest(
                        container,
                        bundle,
                        ckdFetchRecordZonesOperation,
                        UUID.randomUUID().toString(),
                        backupAccount,
                        mbksync,
                        ckUserId,
                        info);
        logger.debug("-- main() - backups request: {}", requestB);

        HttpUriRequest postB = ProtoBufsRequestFactory.defaultInstance().newRequest(
                ckInit.production().url() + "/record/retrieve",
                container,
                bundle,
                ckInit.cloudKitUserId(),
                tokens.cloudKitToken(),
                UUID.randomUUID().toString(),
                Arrays.asList(requestB),
                coreHeaders);

        List<CloudKit.Response> responseB = httpClient.execute(postB, ckResponseHandler);
        logger.debug("-- main() - backup response: {}", responseB);

        List<String> devices = responseB.stream()
                .map(CloudKit.Response::getM211Response)
                .map(CloudKit.M211Response::getBody)
                .map(CloudKit.M211ResponseBody::getContainerList)
                .flatMap(Collection::stream)
                .filter(value -> value.getName().getValue().equals("devices"))
                .flatMap(value -> value.getData().getDataList().stream())
                .map(x -> x.getXRecordID().getRecordID().getRecordName().getValue()) // TODO refactor
                .collect(Collectors.toList());
        logger.info("-- main() - devices: {}", devices);

        if (devices.isEmpty()) {
            logger.info("-- main() - no devices");
            System.exit(0);
        }

        /* 
         STEP 6. Snapshot list.
        
         Url/ headers as step 5.
         Message type 211 with the required backup uuid, protobuf array encoded.

         Returns device/ snapshots/ keybag information.
         Timestamps are hex encoded double offsets to 01 Jan 2001 00:00:00 GMT (Cocoa/ Webkit reference date).
        
         */
        logger.info("-- main() - STEP 6. Snapshot list.");
        CloudKit.String device = CloudKit.String.newBuilder()
                .setValue(devices.get(0)) // First device only.
                .setEncoding(1)
                .build();

        CloudKit.Request requestC = M211RequestFactory.instance()
                .newRequest(
                        container,
                        bundle,
                        ckdFetchRecordZonesOperation,
                        UUID.randomUUID().toString(),
                        device,
                        mbksync,
                        ckUserId,
                        info);
        logger.debug("-- main() - snapshots request: {}", requestC);

        HttpUriRequest postC = ProtoBufsRequestFactory.defaultInstance().newRequest(
                ckInit.production().url() + "/record/retrieve",
                container,
                bundle,
                ckInit.cloudKitUserId(),
                tokens.cloudKitToken(),
                UUID.randomUUID().toString(),
                Arrays.asList(requestC),
                coreHeaders);

        List<CloudKit.Response> responseC = httpClient.execute(postC, ckResponseHandler);
        logger.debug("-- main() - snapshots response: {}", responseC);

        List<String> snapshots = responseC.stream()
                .map(CloudKit.Response::getM211Response)
                .map(CloudKit.M211Response::getBody)
                .map(CloudKit.M211ResponseBody::getContainerList)
                .flatMap(Collection::stream)
                .filter(value -> value.getName().getValue().equals("snapshots"))
                .flatMap(value -> value.getData().getDataList().stream())
                .map(x -> x.getXRecordID().getRecordID().getRecordName().getValue())
                .collect(Collectors.toList());
        logger.info("-- main() - snapshots: {}", snapshots);

        if (snapshots.isEmpty()) {
            logger.info("-- main() - no snapshots for device: {}", devices.get(0));
            System.exit(0);
        }

        /* 
         STEP 7. Manifest list.
        
         Url/ headers as step 6.
         Message type 211 with the required snapshot uuid, protobuf array encoded.

         Returns system/ backup properties (bytes ? format ?? proto), quota information and manifest details.
        
         */
        logger.info("-- main() - STEP 7. Manifest list.");
        CloudKit.String snapshot = CloudKit.String.newBuilder()
                .setValue(snapshots.get(0)) // First snapshot only.
                .setEncoding(1)
                .build();

        CloudKit.Request requestD = M211RequestFactory.instance()
                .newRequest(
                        container,
                        bundle,
                        ckdFetchRecordZonesOperation,
                        UUID.randomUUID().toString(),
                        snapshot,
                        mbksync,
                        ckUserId,
                        info);
        logger.debug("-- main() - manifests request: {}", requestD);

        HttpUriRequest postD = ProtoBufsRequestFactory.defaultInstance().newRequest(
                ckInit.production().url() + "/record/retrieve",
                container,
                bundle,
                ckInit.cloudKitUserId(),
                tokens.cloudKitToken(),
                UUID.randomUUID().toString(),
                Arrays.asList(requestD),
                coreHeaders);

        List<CloudKit.Response> responseD = httpClient.execute(postD, ckResponseHandler);
        logger.debug("-- main() - manifests response: {}", responseD);

        List<String> manifests = responseD.stream()
                .map(CloudKit.Response::getM211Response)
                .map(CloudKit.M211Response::getBody)
                .map(CloudKit.M211ResponseBody::getContainerList)
                .flatMap(Collection::stream)
                .filter(value -> value.getName().getValue().equals("manifestIDs"))
                .map(CloudKit.Container::getData)
                .map(CloudKit.Data::getDataList)
                .flatMap(Collection::stream)
                .map(CloudKit.Data::getString)
                .collect(Collectors.toList());
        logger.info("-- main() - manifests: {}", manifests);

        if (manifests.isEmpty()) {
            logger.info("-- main() - no manifests for snapshot: {}", snapshots.get(0));
            System.exit(0);
        }

// TODO revisit this method, we may be able to reduce the subsequent two server calls to one.
//        /* 
//         STEP 8. Retrieve list of assets
//        
//
//         Url ckDatabase from ckInit + /query/retrieve
//         ~ pXX-ckdatabase.icloud.com:443//api/client/query/retrieve
//         Headers as step 7.
//         Message type 220 with the required manifest string + ":0" appended, protobuf array encoded.
//         List of required fields "fileType", "protectionClass", "encryptedAttributes", "deleted", "keybag"
//        
//         Returns a rather somewhat familiar looking set of results but with encoded bytes.
//         Are these protobufs in protobufs?
//         */
//        logger.info("-- main() - STEP 8 (obsolete). Retrieve list of assets");
//        CloudKit.Names columns = CloudKit.Names.newBuilder()
//                .addName(CloudKit.Name.newBuilder().setValue("fileType").build())
//                .addName(CloudKit.Name.newBuilder().setValue("protectionClass").build())
//                .addName(CloudKit.Name.newBuilder().setValue("encryptedAttributes").build())
//                .addName(CloudKit.Name.newBuilder().setValue("deleted").build())
//                .addName(CloudKit.Name.newBuilder().setValue("keybag").build())
//                .build();
//
//        CloudKit.Name field = CloudKit.Name.newBuilder().setValue("___recordID").build();
//        CloudKit.Name subField = CloudKit.Name.newBuilder().setValue("PrivilegedManifestDownload").build(); // record type
//
//        CloudKit.String manifest = CloudKit.String.newBuilder()
//                .setValue(manifests.get(0) + ":0")
//                .setEncoding(1)
//                .build();
//
//        CloudKit.String defaultZone = CloudKit.String.newBuilder()
//                .setValue("_defaultZone")
//                .setEncoding(6)
//                .build();
//
//        CloudKit.Request requestE = M220RequestFactory.instance()
//                .newRequest(
//                        container,
//                        bundle,
//                        "CKDQueryOperation",
//                        UUID.randomUUID().toString(),
//                        subField,
//                        field,
//                        columns,
//                        manifest,
//                        defaultZone,
//                        ckUserId,
//                        info);
//        logger.debug("-- main() - asset request: {}", requestE);
//
//        HttpUriRequest postE = ProtoBufsRequestFactory.defaultInstance().newRequest(
//                ckInit.production().url() + "/query/retrieve",
//                container,
//                bundle,
//                ckInit.cloudKitUserId(),
//                tokens.cloudKitToken(),
//                UUID.randomUUID().toString(),
//                Arrays.asList(requestE),
//                coreHeaders);
//
//        List<CloudKit.Response> responseE = httpClient.execute(postE, ckResponseHandler);
//        logger.debug("-- main() - assets response: {}", responseE);

        /* 
         STEP 8. Retrieve list of files.
    
         Url/ headers as step 7.
         Message type 211 with the required manifest, protobuf array encoded.

         Returns system/ backup properties (bytes ? format ?? proto), quota information and manifest details.
        
         Returns a rather somewhat familiar looking set of results but with encoded bytes.
         Are these protobufs in protobufs?
         */
        logger.info("-- main() - STEP 8. Retrieve list of assets");
        CloudKit.String manifest = CloudKit.String.newBuilder()
                .setValue(manifests.get(0) + ":0") // Increment if this manifest contains only 0 length files in step 9.
                .setEncoding(1)
                .build();

        CloudKit.Request requestF = M211RequestFactory.instance()
                .newRequest(
                        container,
                        bundle,
                        ckdFetchRecordZonesOperation,
                        UUID.randomUUID().toString(),
                        manifest,
                        defaultZone,
                        ckUserId,
                        info);
        logger.debug("-- main() - assets request: {}", requestF);

        HttpUriRequest postF = ProtoBufsRequestFactory.defaultInstance().newRequest(
                ckInit.production().url() + "/record/retrieve",
                container,
                bundle,
                ckInit.cloudKitUserId(),
                tokens.cloudKitToken(),
                UUID.randomUUID().toString(),
                Arrays.asList(requestF),
                coreHeaders);

        List<CloudKit.Response> responseF = httpClient.execute(postF, ckResponseHandler);
        logger.debug("-- main() - assets response: {}", responseF);

        List<String> files = responseF.stream()
                .map(CloudKit.Response::getM211Response)
                .map(CloudKit.M211Response::getBody)
                .map(CloudKit.M211ResponseBody::getContainerList)
                .flatMap(Collection::stream)
                .filter(c -> c.getName().getValue().equals("files"))
                .map(CloudKit.Container::getData)
                .map(CloudKit.Data::getDataList)
                .flatMap(Collection::stream)
                .map(CloudKit.Data::getXRecordID)
                .map(CloudKit.XRecordID::getRecordID)
                .map(CloudKit.RecordID::getRecordName)
                .map(CloudKit.String::getValue)
                .collect(Collectors.toList());

        logger.debug("-- main() - assets: {}", files);

        /* 
         STEP 9. Retrieve list of asset tokens.
    
         Url/ headers as step 8.
         Message type 211 with the required file, protobuf array encoded.
         */
        logger.info("-- main() - STEP 9. Retrieve list of asset tokens");

        // F:UUID:token:length:x
        // Find first file that isn't 0 length.
        // Not ideal, as all files might be zero length in this manifest, but allows us a flat sequence of steps.
        String file = files.stream()
                .filter(a -> {
                    // TOFIX fragile
                    String[] split = a.split(":");
                    return !split[3].equals("0");
                })
                .findFirst()
                .orElseGet(null);

        if (file == null) {
            logger.warn("-- main() - empty manifest, please manually increment code manifest at step 8");
            System.exit(0);
        }

        CloudKit.String fileRecord = CloudKit.String.newBuilder()
                .setValue(file)
                .setEncoding(1)
                .build();

        CloudKit.Request requestG = M211RequestFactory.instance()
                .newRequest(
                        container,
                        bundle,
                        ckdFetchRecordZonesOperation,
                        UUID.randomUUID().toString(),
                        fileRecord,
                        defaultZone,
                        ckUserId,
                        info);
        logger.debug("-- main() - asset tokens request: {}", requestG);

        HttpUriRequest postG = ProtoBufsRequestFactory.defaultInstance().newRequest(
                ckInit.production().url() + "/record/retrieve",
                container,
                bundle,
                ckInit.cloudKitUserId(),
                tokens.cloudKitToken(),
                UUID.randomUUID().toString(),
                Arrays.asList(requestG),
                coreHeaders);

        List<CloudKit.Response> responseG = httpClient.execute(postG, ckResponseHandler);
        logger.debug("-- main() - asset tokens response: {}", responseG);

        /* 
         STEP 10. AuthorizeGet.
        
         Process somewhat different to iOS8.
        
         New headers/ mmcs auth token. See AuthorizeGetRequestFactory for details.

         Returns a ChunkServer.FileGroup protobuf which is largely identical to iOS8.        
         */
        logger.info("-- main() - STEP 10. AuthorizeGet");

        // Contents are protobufs within protobufs
        // They parse to form a file attributes object that contains our tokens.
        List<ByteString> contents = responseG.stream()
                .map(CloudKit.Response::getM211Response)
                .map(CloudKit.M211Response::getBody)
                .map(CloudKit.M211ResponseBody::getContainerList)
                .flatMap(Collection::stream)
                .filter(c -> c.getName().getValue().equals("contents"))
                .map(c -> c.getData())
                .map(x -> x.getProto())
                .collect(Collectors.toList());

        ByteString content = contents.get(0);

        // TODO confusing, rename to file attributes or similar
        CloudKit.Asset asset = CloudKit.Asset.parseFrom(content);
        logger.debug("-- main() - file attributes: {}", asset);

        // TODO check mmcsurl and com.apple.Dataclass.Content url match. But is there a reason they shouldn't?
        // TODO these encrypted attributes needs deciphering. 
        // TODO Without them we don't know what our binary file data represents.
        // TODO But where is our decryption key?
//        List<ByteString> encryptedAttributes = responseG.stream()
//                .map(CloudKit.Response::getM211Response)
//                .map(CloudKit.M211Response::getBody)
//                .map(CloudKit.M211ResponseBody::getContainerList)
//                .flatMap(Collection::stream)
//                .filter(c -> c.getName().getValue().equals("encryptedAttributes"))
//                .map(c -> c.getData())
//                .map(x -> x.getBytes())
//                .collect(Collectors.toList());
        CloudKit.FileTokens fileTokens = FileTokensFactory.instance().apply(Arrays.asList(asset));

        HttpUriRequest authorizeGet = new AuthorizeGetRequestFactory(coreHeaders)
                .newRequest(auth.dsPrsID(), asset.getMmcsurl(), container, "_defaultZone", fileTokens);

        logger.debug("-- main() - authorizeGet request: {}", authorizeGet);

        ResponseHandler<ChunkServer.FileGroups> fileGroupsResponseHandler
                = new InputStreamResponseHandler<>(ChunkServer.FileGroups.PARSER::parseFrom);

        ChunkServer.FileGroups fileGroups = httpClient.execute(authorizeGet, fileGroupsResponseHandler);

        logger.debug("-- main() - fileGroups: {}", fileGroups);

    }

}
// TODO info limits have not been set
