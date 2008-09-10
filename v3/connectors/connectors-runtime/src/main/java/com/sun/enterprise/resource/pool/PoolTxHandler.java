/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.resource.pool;

import com.sun.enterprise.resource.ResourceHandle;
import com.sun.enterprise.resource.ResourceState;
import com.sun.enterprise.connectors.ConnectorRuntime;
import com.sun.enterprise.transaction.api.JavaEETransaction;
import com.sun.logging.LogDomains;

import javax.transaction.SystemException;
import javax.transaction.Transaction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.*;


/**
 * Transaction helper for the pool to check various states of a resource that is taking part in the transaction.
 * @author Jagadish Ramu
 */
public class PoolTxHandler {

    private String poolName;

    protected final static Logger _logger = LogDomains.getLogger(PoolTxHandler.class, LogDomains.RSR_LOGGER);

    public PoolTxHandler(String poolName){
        this.poolName = poolName;
    }

    /**
     * Check whether the local resource can be put back to pool
     * If true, unenlist the resource
     *
     * @param h ResourceHandle to be verified
     * @return boolean
     */
    public boolean isLocalResourceEligibleForReuse(ResourceHandle h) {
        boolean result = false;
        if ((!isLocalResourceInTransaction(h))) {
            try {
                enforceDelistment(h);
            } catch (SystemException se) {
                _logger.log(Level.FINE, "Exception while delisting the local resource [ of pool : "+poolName+" ] " +
                        "forcibily from transaction", se);
                return result;
            }
            h.getResourceState().setEnlisted(false);
            result = true;
        }
        return result;
    }

    /**
     * Remove the resource from book-keeping
     *
     * @param h ResourceHandle to be delisted
     * @throws javax.transaction.SystemException when not able to delist the resource
     */
    private void enforceDelistment(ResourceHandle h) throws SystemException {
        JavaEETransaction txn = (JavaEETransaction) ConnectorRuntime.getRuntime().getTransaction();
        if (txn != null) {
            Set set = txn.getResources(poolName);
            if (set != null)
                set.remove(h);
        }
    }

    /**
     * Check whether the local resource in question is the one participating in transaction.
     *
     * @param h ResourceHandle
     * @return true if the resource is  participating in the transaction
     */
    public boolean isLocalResourceInTransaction(ResourceHandle h) {
        boolean result = true;
        try {
            JavaEETransaction txn = (JavaEETransaction) ConnectorRuntime.getRuntime().getTransaction();
            if (txn != null)
                result = isNonXAResourceInTransaction(txn, h);
        } catch (SystemException e) {
            _logger.log(Level.FINE, "Exception while checking whether the resource [ of pool : "+poolName+" ] " +
                    "is nonxa and is enlisted in transaction : ", e);
        }
        return result;
    }

    /**
     * Check whether the resource is non-xa
     *
     * @param resource Resource to be verified
     * @return boolean indicating whether the resource is non-xa
     */
    public boolean isNonXAResource(ResourceHandle resource) {
        return !resource.getResourceSpec().isXA();
    }

    /**
     * Check whether the non-xa resource is enlisted in transaction.
     *
     * @param tran     Transaction
     * @param resource Resource to be verified
     * @return boolean indicating whether thegiven non-xa  resource is in transaction
     */
    private boolean isNonXAResourceInTransaction(JavaEETransaction tran, ResourceHandle resource) {

        return resource.equals(tran.getNonXAResource());
    }

    /**
     * Check whether the resource is non-xa, free and is enlisted in transaction.
     *
     * @param tran     Transaction
     * @param resource Resource to be verified
     * @return boolean indicating whether the resource is free, non-xa and is enlisted in transaction
     */
    public boolean isNonXAResourceAndFree(JavaEETransaction tran, ResourceHandle resource) {
        return resource.getResourceState().isFree() && isNonXAResource(resource) && isNonXAResourceInTransaction(tran, resource);
    }

    /**
     * this method is called when a resource is enlisted in
     * transation tran
     * @param tran Transaction to which the resource need to be enlisted
     * @param resource Resource to be enlisted in the transaction
     */
    public void resourceEnlisted(Transaction tran, ResourceHandle resource) {
        try {
            JavaEETransaction j2eetran = (JavaEETransaction) tran;
            Set set = j2eetran.getResources(poolName);
            if (set == null) {
                set = new HashSet();
                j2eetran.setResources(set, poolName);
            }
            set.add(resource);
        } catch (ClassCastException e) {
            _logger.log(Level.FINE, "Pool [ "+poolName+" ]: resourceEnlisted:" +
                    "transaction is not J2EETransaction but a " + tran.getClass().getName(), e);
        }
        ResourceState state = resource.getResourceState();
        state.setEnlisted(true);
        if (_logger.isLoggable(Level.FINE)) {
            _logger.log(Level.FINE, "Pool [ "+poolName+" ]: resourceEnlisted: " + resource);
        }
    }

    /**
     * this method is called when transaction tran is completed
     * @param tran transaction which has completed
     * @param status transaction status
     * @param poolName Pool name
     * @return delisted resources
     */
    public List<ResourceHandle> transactionCompleted(Transaction tran, int status, String poolName) {
        JavaEETransaction j2eetran;
        List<ResourceHandle> delistedResources = new ArrayList<ResourceHandle>();
        try {
             j2eetran = (JavaEETransaction) tran;
        } catch (ClassCastException e) {
            _logger.log(Level.FINE, "Pool: transactionCompleted: " +
                    "transaction is not J2EETransaction but a " + tran.getClass().getName(), e);
            return delistedResources;
        }
        Set set = j2eetran.getResources(poolName);

        if (set == null) return delistedResources;

        Iterator iter = set.iterator();
        while (iter.hasNext()) {
            ResourceHandle resource = (ResourceHandle) iter.next();
            ResourceState state = resource.getResourceState();
            state.setEnlisted(false);
            delistedResources.add(resource);
            iter.remove();
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "Pool: transactionCompleted: " + resource);
            }
        }
        return delistedResources;
    }
}
