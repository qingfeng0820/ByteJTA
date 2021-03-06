/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytejta;

import java.util.List;
import java.util.Map;

import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.xa.XAResource;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.resource.RemoteResourceDescriptor;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.transaction.CommitRequiredException;
import org.bytesoft.transaction.RollbackRequiredException;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionRecovery;
import org.bytesoft.transaction.TransactionRepository;
import org.bytesoft.transaction.archive.TransactionArchive;
import org.bytesoft.transaction.archive.XAResourceArchive;
import org.bytesoft.transaction.aware.TransactionBeanFactoryAware;
import org.bytesoft.transaction.logging.TransactionLogger;
import org.bytesoft.transaction.recovery.TransactionRecoveryCallback;
import org.bytesoft.transaction.recovery.TransactionRecoveryListener;
import org.bytesoft.transaction.supports.resource.XAResourceDescriptor;
import org.bytesoft.transaction.xa.TransactionXid;
import org.bytesoft.transaction.xa.XidFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionRecoveryImpl implements TransactionRecovery, TransactionBeanFactoryAware {
	static final Logger logger = LoggerFactory.getLogger(TransactionRecoveryImpl.class);

	private TransactionBeanFactory beanFactory;
	private TransactionRecoveryListener listener;

	public synchronized void timingRecover() {
		TransactionRepository transactionRepository = beanFactory.getTransactionRepository();
		List<Transaction> transactions = transactionRepository.getErrorTransactionList();
		int total = transactions == null ? 0 : transactions.size();
		int value = 0;
		for (int i = 0; transactions != null && i < transactions.size(); i++) {
			Transaction transaction = transactions.get(i);
			TransactionContext transactionContext = transaction.getTransactionContext();
			TransactionXid xid = transactionContext.getXid();
			try {
				this.recoverTransaction(transaction);
			} catch (CommitRequiredException ex) {
				logger.debug("[{}] recover: branch={}, message= commit-required",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()));
				continue;
			} catch (RollbackRequiredException ex) {
				logger.debug("[{}] recover: branch={}, message= rollback-required",
						ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()));
				continue;
			} catch (SystemException ex) {
				logger.debug("[{}] recover: branch={}, message= {}", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage());
				continue;
			} catch (RuntimeException ex) {
				logger.debug("[{}] recover: branch={}, message= {}", ByteUtils.byteArrayToString(xid.getGlobalTransactionId()),
						ByteUtils.byteArrayToString(xid.getBranchQualifier()), ex.getMessage());
				continue;
			}
		}
		logger.debug("[transaction-recovery] total= {}, success= {}", total, value);
	}

	public void recoverTransaction(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {

		TransactionContext transactionContext = transaction.getTransactionContext();
		boolean coordinator = transactionContext.isCoordinator();
		if (coordinator) {
			transaction.recover();
			this.recoverCoordinator(transaction);
		} else {
			transaction.recover();
			this.recoverParticipant(transaction);
		}

	}

	private void recoverCoordinator(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {

		switch (transaction.getTransactionStatus()) {
		case Status.STATUS_ACTIVE:
		case Status.STATUS_MARKED_ROLLBACK:
		case Status.STATUS_PREPARING:
		case Status.STATUS_ROLLING_BACK:
		case Status.STATUS_UNKNOWN:
			transaction.recoveryRollback();
			transaction.forgetQuietly();
			break;
		case Status.STATUS_PREPARED:
		case Status.STATUS_COMMITTING:
			transaction.recoveryCommit();
			transaction.forgetQuietly();
			break;
		case Status.STATUS_COMMITTED:
		case Status.STATUS_ROLLEDBACK:
			transaction.forgetQuietly();
			break;
		default:
			logger.debug("Current transaction has already been completed.");
		}
	}

	private void recoverParticipant(Transaction transaction)
			throws CommitRequiredException, RollbackRequiredException, SystemException {

		TransactionImpl transactionImpl = (TransactionImpl) transaction;
		switch (transaction.getTransactionStatus()) {
		case Status.STATUS_PREPARED:
		case Status.STATUS_COMMITTING:
			break;
		case Status.STATUS_COMMITTED:
		case Status.STATUS_ROLLEDBACK:
			break;
		case Status.STATUS_ACTIVE:
		case Status.STATUS_MARKED_ROLLBACK:
		case Status.STATUS_PREPARING:
		case Status.STATUS_UNKNOWN:
		case Status.STATUS_ROLLING_BACK:
		default:
			transactionImpl.recoveryRollback();
			transactionImpl.forgetQuietly();
		}
	}

	public synchronized void startRecovery() {
		final TransactionRepository transactionRepository = beanFactory.getTransactionRepository();
		final TransactionLogger transactionLogger = beanFactory.getTransactionLogger();
		transactionLogger.recover(new TransactionRecoveryCallback() {

			public void recover(TransactionArchive archive) {
				try {
					TransactionImpl transaction = reconstructTransaction(archive);
					if (listener != null) {
						listener.onRecovery(transaction);
					}
					TransactionContext transactionContext = transaction.getTransactionContext();
					TransactionXid globalXid = transactionContext.getXid();
					transactionRepository.putTransaction(globalXid, transaction);
					transactionRepository.putErrorTransaction(globalXid, transaction);
				} catch (IllegalStateException ex) {
					transactionLogger.deleteTransaction(archive);
				}

			}
		});

		TransactionCoordinator transactionCoordinator = //
				(TransactionCoordinator) this.beanFactory.getTransactionCoordinator();
		transactionCoordinator.markParticipantReady();
	}

	private TransactionImpl reconstructTransaction(TransactionArchive archive) throws IllegalStateException {
		XidFactory xidFactory = this.beanFactory.getXidFactory();
		TransactionContext transactionContext = new TransactionContext();
		TransactionXid xid = (TransactionXid) archive.getXid();
		transactionContext.setXid(xidFactory.createGlobalXid(xid.getGlobalTransactionId()));
		transactionContext.setRecoveried(true);
		transactionContext.setCoordinator(archive.isCoordinator());
		transactionContext.setPropagatedBy(archive.getPropagatedBy());

		TransactionImpl transaction = new TransactionImpl(transactionContext);
		transaction.setBeanFactory(this.beanFactory);
		transaction.setTransactionStatus(archive.getStatus());

		List<XAResourceArchive> nativeResources = archive.getNativeResources();
		transaction.getNativeParticipantList().addAll(nativeResources);

		transaction.setParticipant(archive.getOptimizedResource());

		List<XAResourceArchive> remoteResources = archive.getRemoteResources();
		transaction.getRemoteParticipantList().addAll(remoteResources);

		List<XAResourceArchive> participants = transaction.getParticipantList();
		Map<String, XAResourceArchive> applicationMap = transaction.getApplicationMap();
		Map<String, XAResourceArchive> participantMap = transaction.getParticipantMap();
		if (archive.getOptimizedResource() != null) {
			participants.add(archive.getOptimizedResource());
		}
		participants.addAll(nativeResources);
		participants.addAll(remoteResources);

		for (int i = 0; i < participants.size(); i++) {
			XAResourceArchive element = participants.get(i);
			XAResourceDescriptor descriptor = element.getDescriptor();
			String identifier = StringUtils.trimToEmpty(descriptor.getIdentifier());

			if (RemoteResourceDescriptor.class.isInstance(descriptor)) {
				RemoteResourceDescriptor resourceDescriptor = (RemoteResourceDescriptor) descriptor;
				RemoteCoordinator remoteCoordinator = resourceDescriptor.getDelegate();
				applicationMap.put(remoteCoordinator.getApplication(), element);
			} // end-if (RemoteResourceDescriptor.class.isInstance(descriptor))

			participantMap.put(identifier, element);
		}

		transaction.recoverTransactionStrategy(archive.getTransactionStrategyType());

		if (archive.getVote() == XAResource.XA_RDONLY) {
			throw new IllegalStateException("Transaction has already been completed!");
		}

		return transaction;
	}

	public void setBeanFactory(TransactionBeanFactory tbf) {
		this.beanFactory = tbf;
	}

	public TransactionRecoveryListener getListener() {
		return listener;
	}

	public void setListener(TransactionRecoveryListener listener) {
		this.listener = listener;
	}
}
