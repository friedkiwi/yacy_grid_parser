/**
 *  ParserService
 *  Copyright 1.4.2017 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *  
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *  
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.parser.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jwat.common.HeaderLine;
import org.jwat.common.HttpHeader;
import org.jwat.warc.WarcConstants;
import org.jwat.warc.WarcReader;
import org.jwat.warc.WarcReaderFactory;
import org.jwat.warc.WarcRecord;

import net.yacy.cora.protocol.HeaderFramework;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.cora.util.ByteBuffer;
import net.yacy.crawler.retrieval.Request;
import net.yacy.crawler.retrieval.Response;
import net.yacy.document.Document;
import net.yacy.document.Parser;
import net.yacy.document.TextParser;
import net.yacy.grid.http.APIHandler;
import net.yacy.grid.http.ClientIdentification;
import net.yacy.grid.http.ObjectAPIHandler;
import net.yacy.grid.http.Query;
import net.yacy.grid.http.ServiceResponse;
import net.yacy.grid.http.RemoteAccess;
import net.yacy.grid.io.assets.Asset;
import net.yacy.grid.mcp.Data;
import net.yacy.grid.tools.AnchorURL;
import net.yacy.grid.tools.MultiProtocolURL;
import net.yacy.search.schema.CollectionConfiguration;
import net.yacy.server.http.ChunkedInputStream;

/**
 * The parser transforms a given source file into a YaCy JSON
 * The source must be defined as WARC file. There may be three sources for a WARC file:
 * - (1) within the POST request
 * - (2) within a GET request
 * - (3) as an asset, stored by the mcp
 * - (4) as a source url, hosted somewhere .. maybe also within a file in the file system.
 * 
 * The target can be returned in several ways as well
 * - (1) as the json result of the request
 * - (2) stored within a remote storage location
 * - (3) as an asset, stored by the mcp
 * 
 * The whole process may have a steering attached with actions that are used to
 * store processes on the mcp queue.
 * - All i/o combination mentioned above must be available in a queued process. 
 * Possible post-process steps may be
 * - send the result to an indexer
 * - extract urls and store them in a crawl queue (including a link double-check)
 * - tell a log client to print out the status of the operation
 * - move the WARC file to an archive position
 * 
 * test:
 * 
 * - first call
 * cd ~/Downloads
 * wget https://www.land.nrw/ --warc-file=land.nrw
 * 
 * - then read the warc with
 * http://127.0.0.1:8500/yacy/grid/parser/parser.json?sourceurl=file:///Users/admin/Downloads/land.nrw.warc.gz
 * 
 * - to test with an asset, first store the warc to the asset store:
 * curl --request POST --form "asset=@land.nrw.warc.gz;type=application/octet-stream" --form "path=/test/land.nrw.warc.gz" http://127.0.0.1:8500/yacy/grid/mcp/assets/store.json
 * 
 * - read the asset with
 * http://127.0.0.1:8500/yacy/grid/parser/parser.json?sourceasset=test/land.nrw.warc.gz
 */
public class ParserService extends ObjectAPIHandler implements APIHandler {

    private static final long serialVersionUID = 8578474303031749879L;
    public static final String NAME = "parser";
    
    @Override
    public String getAPIPath() {
        return "/yacy/grid/parser/" + NAME + ".json";
    }
    
    /**
     * load a WARC from the sourcepath and store json flat file into targetpath
     * source is given by one of the following attributes
     * - sourcebytes: either a POSTed binary object of a WARC or called with GET of a WARC
     * - sourceasset: a name of a MCP asset containing a WARC file
     * - sourceurl: an url with a path of a hosted WARC file
     */
    @Override
    public ServiceResponse serviceImpl(Query call, HttpServletResponse response) {

        JSONObject json = new JSONObject(true);
        
        // get the post map to read binaries from a POST call
        Map<String, byte[]> postMap;
        try {
            postMap = RemoteAccess.getPostMap(call.getRequest());
        } catch (IOException e) {
            postMap = new HashMap<>();
        }

        InputStream sourceStream = null;
        
        // read the source asset. We have four options:
        // 1) get the asset from the POST request in field 'sourceasset'
        byte[] source = postMap.get("sourcebytes");
        if (source != null) {
            sourceStream = new ByteArrayInputStream(source);
        }
        // 2) get the asset from a string in a GET request
        if (sourceStream == null) {
            String sas = call.get("sourcebytes");
            if (sas != null) {
                source = sas.getBytes(StandardCharsets.UTF_8);
                sourceStream = new ByteArrayInputStream(source);
            }
        }
        // 3) get the asset from the mcp asset store
        if (sourceStream == null) {
            // read asset from mcp
            String sourceasset = call.get("sourceasset", "");
            if (sourceasset.length() > 0) {
                try {
                    Asset<byte[]> asset = Data.gridStorage.load(sourceasset);
                    source = asset.getPayload();
                    sourceStream = new ByteArrayInputStream(source);
                    if (sourceasset.endsWith(".gz")) sourceStream = new GZIPInputStream(sourceStream);
                } catch (IOException e) {
                    Data.logger.error(e.getMessage(), e);
                }
            }
        }
       
        // 4) get the asset from an external resource
        if (sourceStream == null) {
            // read from url
            String urlstring = call.get("sourceurl", "");
            if (urlstring.length() > 0) try {
                MultiProtocolURL url = new MultiProtocolURL(urlstring);
                sourceStream = url.getInputStream(ClientIdentification.browserAgent, "anonymous", "");
                if (urlstring.endsWith(".gz")) sourceStream = new GZIPInputStream(sourceStream);
            } catch (IOException e) {
                Data.logger.error(e.getMessage(), e);
            }
        }
        
        if (sourceStream == null) {
            json.put(ObjectAPIHandler.SUCCESS_KEY, false);
            json.put(ObjectAPIHandler.COMMENT_KEY, "the request must contain either a sourcebytes, sourceasset or sourceurl attribute");
            return new ServiceResponse(json);
        }
        
        // compute parsed documents
        JSONArray parsedDocuments;
        try {
            parsedDocuments = indexWarcRecords(sourceStream);
        } catch (IOException e) {
            e.printStackTrace();
            parsedDocuments = new JSONArray();
        } finally {
            try {
                sourceStream.close();
            } catch (IOException e) {
            }
        }
        
        // store result and return success
        json.put("documents", parsedDocuments);
        
        /*
        if (targetasset.length > 0 && targetpath.length() > 0) {
            try {
                StorageFactory<byte[]> factory = Data.gridStorage.store(targetpath, targetasset);
                String url = factory.getConnectionURL();
                json.put(JSONAPIHandler.SUCCESS_KEY, true);
                if (url != null) json.put(JSONAPIHandler.SERVICE_KEY, url);
            } catch (IOException e) {
                json.put(JSONAPIHandler.SUCCESS_KEY, false);
                json.put(JSONAPIHandler.COMMENT_KEY, e.getMessage());
            }
        } else {
            json.put(JSONAPIHandler.SUCCESS_KEY, false);
            json.put(JSONAPIHandler.COMMENT_KEY, "the request must contain a sourcepath or sourceasset and a targetpath");
        }
        */

        json.put(ObjectAPIHandler.SUCCESS_KEY, true);
        return new ServiceResponse(json);
    }
    
    /**
     * WARC importer code from net.yacy.document.importer.WarcImporter.java 
     * @param f
     * @throws IOException
     */
    public JSONArray indexWarcRecords(InputStream f) throws IOException {

        JSONArray parsedDocuments = new JSONArray();
        byte[] content;
        int cnt = 0;

        WarcReader localwarcReader = WarcReaderFactory.getReader(f);
        WarcRecord wrec = localwarcReader.getNextRecord();
        while (wrec != null) {

            HeaderLine hl = wrec.getHeader(WarcConstants.FN_WARC_TYPE);
            if (hl != null && hl.value.equals(WarcConstants.RT_RESPONSE)) { // filter responses

                hl = wrec.getHeader(WarcConstants.FN_WARC_TARGET_URI);
                MultiProtocolURL location = new MultiProtocolURL(hl.value);

                HttpHeader http = wrec.getHttpHeader();

                if (http != null && http.statusCode == 200) { // process http response header OK (status 200)

                    if (TextParser.supportsMime(http.contentType) == null) { // check availability of parser

                        InputStream istream = wrec.getPayloadContent();
                        hl = http.getHeader(HeaderFramework.TRANSFER_ENCODING);
                        if (hl != null && hl.value.contains("chunked")) {
                            // because chunked stream.read doesn't read source fully, make sure all chunks are read
                            istream = new ChunkedInputStream(istream);
                            final ByteBuffer bbuffer = new ByteBuffer();
                            int c;
                            while ((c = istream.read()) >= 0) {
                                bbuffer.append(c);
                            }
                            content = bbuffer.getBytes();
                        } else {
                            content = new byte[(int) http.getPayloadLength()];
                            istream.read(content, 0, content.length);
                        }
                        istream.close();

                        RequestHeader requestHeader = new RequestHeader();

                        ResponseHeader responseHeader = new ResponseHeader(http.statusCode);
                        for (HeaderLine hx : http.getHeaderList()) { // include all original response headers for parser
                            responseHeader.put(hx.name, hx.value);
                        }

                        final Request request = new Request(
                                null,
                                location,
                                requestHeader.referer() == null ? null : requestHeader.referer(),
                                "warc",
                                responseHeader.lastModified(),
                                0);

                        final Response response = new Response(
                                request,
                                requestHeader,
                                responseHeader,
                                false,
                                content
                        );

                        try {
                            // parse the source to get a YaCy document
                            Document[] documents =
                                TextParser.parseSource(
                                    new AnchorURL(response.url()), // or just use "location"?
                                    response.getMimeType(),
                                    response.getCharacterEncoding(),
                                    null, // no vocabulary scraper
                                    0, // no timezone offset
                                    response.depth(),
                                    response.getContent());
                            // transform the YaCy document into a JSON
                            for (Document d: documents) {
                                JSONObject json = CollectionConfiguration.yacy2solr(
                                        null /* collections */, responseHeader,
                                        d, requestHeader.referer(), null /* language */, false,
                                        0 /* timezoneOffset */);
                                parsedDocuments.put(json);
                            }
                        } catch (Parser.Failure e) {
                            e.printStackTrace();
                        }
                        cnt++;
                    }
                }
            }
            wrec = localwarcReader.getNextRecord();
        }
        localwarcReader.close();
        Data.logger.info("WarcImporter", "Indexed " + cnt + " documents");
        return parsedDocuments;
    }


}
