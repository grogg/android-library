/* ownCloud Android Library is available under MIT license
 *   Copyright (C) 2016 ownCloud GmbH.
 *   
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *   
 *   The above copyright notice and this permission notice shall be included in
 *   all copies or substantial portions of the Software.
 *   
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, 
 *   EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 *   MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND 
 *   NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS 
 *   BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN 
 *   ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN 
 *   CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *   THE SOFTWARE.
 *
 */

package com.joshuaglenlee.ownclient.lib.resources.files;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.Random;

import org.apache.commons.httpclient.methods.PutMethod;

import com.joshuaglenlee.ownclient.lib.common.OwnCloudClient;
import com.joshuaglenlee.ownclient.lib.common.network.ChunkFromFileChannelRequestEntity;
import com.joshuaglenlee.ownclient.lib.common.network.ProgressiveDataTransferer;
import com.joshuaglenlee.ownclient.lib.common.network.WebdavUtils;
import com.joshuaglenlee.ownclient.lib.common.operations.InvalidCharacterExceptionParser;
import com.joshuaglenlee.ownclient.lib.common.operations.RemoteOperationResult;
import com.joshuaglenlee.ownclient.lib.common.utils.Log_OC;


public class ChunkedUploadRemoteFileOperation extends UploadRemoteFileOperation {

    private static final int LAST_CHUNK_TIMEOUT = 900000; //15 mins.

    public static final long CHUNK_SIZE = 1024000;
    private static final String OC_CHUNKED_HEADER = "OC-Chunked";
    private static final String OC_CHUNK_SIZE_HEADER = "OC-Chunk-Size";
    private static final String OC_CHUNK_X_OC_MTIME_HEADER = "X-OC-Mtime";
    private static final String TAG = ChunkedUploadRemoteFileOperation.class.getSimpleName();

    public ChunkedUploadRemoteFileOperation(String storagePath, String remotePath, String mimeType,
                                            String fileLastModifTimestamp) {
        super(storagePath, remotePath, mimeType, fileLastModifTimestamp);
    }

    public ChunkedUploadRemoteFileOperation(
        String storagePath, String remotePath, String mimeType, String requiredEtag,
        String fileLastModifTimestamp
    ) {
        super(storagePath, remotePath, mimeType, requiredEtag, fileLastModifTimestamp);
    }

    @Override
    protected RemoteOperationResult uploadFile(OwnCloudClient client) throws IOException {
        int status = -1;
        RemoteOperationResult result = null;

        FileChannel channel = null;
        RandomAccessFile raf = null;
        try {
            File file = new File(mLocalPath);
            raf = new RandomAccessFile(file, "r");
            channel = raf.getChannel();
            mEntity = new ChunkFromFileChannelRequestEntity(channel, mMimeType, CHUNK_SIZE, file);
            synchronized (mDataTransferListeners) {
                ((ProgressiveDataTransferer) mEntity)
                    .addDatatransferProgressListeners(mDataTransferListeners);
            }

            long offset = 0;
            String uriPrefix = client.getWebdavUri() + WebdavUtils.encodePath(mRemotePath) +
                "-chunking-" + Math.abs((new Random()).nextInt(9000) + 1000) + "-";
            long totalLength = file.length();
            long chunkCount = (long) Math.ceil((double) totalLength / CHUNK_SIZE);
            String chunkSizeStr = String.valueOf(CHUNK_SIZE);
            String totalLengthStr = String.valueOf(file.length());
            for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++, offset += CHUNK_SIZE) {
                if (chunkIndex == chunkCount - 1) {
                    chunkSizeStr = String.valueOf(CHUNK_SIZE * chunkCount - totalLength);
                }
                if (mPutMethod != null) {
                    mPutMethod.releaseConnection();     // let the connection available
                    // for other methods
                }
                mPutMethod = new PutMethod(uriPrefix + chunkCount + "-" + chunkIndex);
                if (mRequiredEtag != null && mRequiredEtag.length() > 0) {
                    mPutMethod.addRequestHeader(IF_MATCH_HEADER, "\"" + mRequiredEtag + "\"");
                }
                mPutMethod.addRequestHeader(OC_CHUNKED_HEADER, OC_CHUNKED_HEADER);
                mPutMethod.addRequestHeader(OC_CHUNK_SIZE_HEADER, chunkSizeStr);
                mPutMethod.addRequestHeader(OC_TOTAL_LENGTH_HEADER, totalLengthStr);

                mPutMethod.addRequestHeader(OC_CHUNK_X_OC_MTIME_HEADER, mFileLastModifTimestamp);

                ((ChunkFromFileChannelRequestEntity) mEntity).setOffset(offset);
                mPutMethod.setRequestEntity(mEntity);
                if (mCancellationRequested.get()) {
                    mPutMethod.abort();
                    // next method will throw an exception
                }

                if (chunkIndex == chunkCount - 1) {
                    // Added a high timeout to the last chunk due to when the last chunk
                    // arrives to the server with the last PUT, all chunks get assembled
                    // within that PHP request, so last one takes longer.
                    mPutMethod.getParams().setSoTimeout(LAST_CHUNK_TIMEOUT);
                }

                status = client.executeMethod(mPutMethod);

                result = new RemoteOperationResult(
                    isSuccess(status),
                    mPutMethod
                );

                client.exhaustResponse(mPutMethod.getResponseBodyAsStream());
                Log_OC.d(TAG, "Upload of " + mLocalPath + " to " + mRemotePath +
                    ", chunk index " + chunkIndex + ", count " + chunkCount +
                    ", HTTP result status " + status);

                if (!isSuccess(status))
                    break;
            }

        } finally {
            if (channel != null)
                channel.close();
            if (raf != null)
                raf.close();
            if (mPutMethod != null)
                mPutMethod.releaseConnection();    // let the connection available for other methods
        }
        return result;
    }

}
