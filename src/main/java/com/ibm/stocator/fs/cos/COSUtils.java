/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ibm.stocator.fs.cos;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.ExecutionException;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.model.AmazonS3Exception;

import com.ibm.stocator.fs.cos.exception.COSClientIOException;
import com.ibm.stocator.fs.cos.exception.COSIOException;
import com.ibm.stocator.fs.cos.exception.COSServiceIOException;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.ibm.stocator.fs.cos.COSConstants.MULTIPART_MIN_SIZE;
import static com.ibm.stocator.fs.cos.COSConstants.ENDPOINT_URL;

/**
 * Utility methods for COS code.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public final class COSUtils {

  private static final Logger LOG = LoggerFactory.getLogger(COSUtils.class);
  static final String ENDPOINT_KEY = "Endpoint";

  private COSUtils() {
  }

  /**
   * Translate an exception raised in an operation into an IOException. The
   * specific type of IOException depends on the class of
   * {@link AmazonClientException} passed in, and any status codes included in
   * the operation. That is: HTTP error codes are examined and can be used to
   * build a more specific response.
   *
   * @param operation operation
   * @param path path operated on (must not be null)
   * @param exception amazon exception raised
   * @return an IOE which wraps the caught exception
   */
  public static IOException translateException(String operation, Path path,
      AmazonClientException exception) {
    return translateException(operation, path.toString(), exception);
  }

  /**
   * Translate an exception raised in an operation into an IOException. The
   * specific type of IOException depends on the class of
   * {@link AmazonClientException} passed in, and any status codes included in
   * the operation. That is: HTTP error codes are examined and can be used to
   * build a more specific response.
   *
   * @param operation operation
   * @param path path operated on (may be null)
   * @param exception amazon exception raised
   * @return an IOE which wraps the caught exception
   */
  @SuppressWarnings("ThrowableInstanceNeverThrown")
  public static IOException translateException(String operation, String path,
      AmazonClientException exception) {
    String message = String.format("%s%s: %s", operation, path != null ? (" on "
        + path) : "", exception);
    if (!(exception instanceof AmazonServiceException)) {
      if (containsInterruptedException(exception)) {
        return (IOException) new InterruptedIOException(message).initCause(exception);
      }
      return new COSClientIOException(message, exception);
    } else {

      IOException ioe;
      AmazonServiceException ase = (AmazonServiceException) exception;
      // this exception is non-null if the service exception is an COS one
      AmazonS3Exception s3Exception = ase instanceof AmazonS3Exception ? (AmazonS3Exception) ase
          : null;
      int status = ase.getStatusCode();
      switch (status) {

        case 301:
          if (s3Exception != null) {
            if (s3Exception.getAdditionalDetails() != null
                && s3Exception.getAdditionalDetails().containsKey(ENDPOINT_KEY)) {
              message = String.format(
                  "Received permanent redirect response to "
                      + "endpoint %s.  This likely indicates that the COS endpoint "
                      + "configured in %s does not match the region containing "
                      + "the bucket.",
                  s3Exception.getAdditionalDetails().get(ENDPOINT_KEY), ENDPOINT_URL);
            }
            ioe = new COSIOException(message, s3Exception);
          } else {
            ioe = new COSServiceIOException(message, ase);
          }
          break;
        // permissions
        case 401:
        case 403:
          ioe = new AccessDeniedException(path, null, message);
          ioe.initCause(ase);
          break;

        // the object isn't there
        case 404:
        case 410:
          ioe = new FileNotFoundException(message);
          ioe.initCause(ase);
          break;

        // out of range. This may happen if an object is overwritten with
        // a shorter one while it is being read.
        case 416:
          ioe = new EOFException(message);
          break;

        default:
          // no specific exit code. Choose an IOE subclass based on the class
          // of the caught exception
          ioe = s3Exception != null ? new COSIOException(message, s3Exception)
              : new COSServiceIOException(message, ase);
          break;
      }
      return ioe;
    }
  }

  /**
   * Extract an exception from a failed future, and convert to an IOE.
   *
   * @param operation operation which failed
   * @param path path operated on (may be null)
   * @param ee execution exception
   * @return an IOE which can be thrown
   */
  public static IOException extractException(String operation, String path,
      ExecutionException ee) {
    IOException ioe;
    Throwable cause = ee.getCause();
    if (cause instanceof AmazonClientException) {
      ioe = translateException(operation, path, (AmazonClientException) cause);
    } else if (cause instanceof IOException) {
      ioe = (IOException) cause;
    } else {
      ioe = new IOException(operation + " failed: " + cause, cause);
    }
    return ioe;
  }

  /**
   * Recurse down the exception loop looking for any inner details about an
   * interrupted exception.
   *
   * @param thrown exception thrown
   * @return true if down the execution chain the operation was an interrupt
   */
  static boolean containsInterruptedException(Throwable thrown) {
    if (thrown == null) {
      return false;
    }
    if (thrown instanceof InterruptedException || thrown instanceof InterruptedIOException) {
      return true;
    }
    // tail recurse
    return containsInterruptedException(thrown.getCause());
  }

  /**
   * Get a size property from the configuration: this property must be at least
   * equal to {@link COSConstants#MULTIPART_MIN_SIZE}. If it is too small, it is
   * rounded up to that minimum, and a warning printed.
   *
   * @param conf configuration
   * @param property property name
   * @param defVal default value
   * @return the value, guaranteed to be above the minimum size
   */
  public static long getMultipartSizeProperty(Configuration conf,
      String property, long defVal) {
    long partSize = conf.getLongBytes(property, defVal);
    if (partSize < MULTIPART_MIN_SIZE) {
      LOG.warn("{} must be at least 5 MB; configured value is {}", property, partSize);
      partSize = MULTIPART_MIN_SIZE;
    }
    return partSize;
  }

  /**
   * Ensure that the long value is in the range of an integer.
   *
   * @param name property name for error messages
   * @param size original size
   * @return the size, guaranteed to be less than or equal to the max value of
   *         an integer
   */
  public static int ensureOutputParameterInRange(String name, long size) {
    if (size > Integer.MAX_VALUE) {
      LOG.warn("cos: {} capped to ~2.14GB"
          + " (maximum allowed size with current output mechanism)", name);
      return Integer.MAX_VALUE;
    } else {
      return (int) size;
    }
  }
}
