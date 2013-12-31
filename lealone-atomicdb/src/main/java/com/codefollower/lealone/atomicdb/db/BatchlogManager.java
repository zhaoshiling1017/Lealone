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
package com.codefollower.lealone.atomicdb.db;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.codefollower.lealone.atomicdb.concurrent.DebuggableScheduledThreadPoolExecutor;
import com.codefollower.lealone.atomicdb.config.CFMetaData;
import com.codefollower.lealone.atomicdb.config.DatabaseDescriptor;
import com.codefollower.lealone.atomicdb.cql.QueryProcessor;
import com.codefollower.lealone.atomicdb.cql.UntypedResultSet;
import com.codefollower.lealone.atomicdb.db.compaction.CompactionManager;
import com.codefollower.lealone.atomicdb.db.composites.CellName;
import com.codefollower.lealone.atomicdb.db.marshal.LongType;
import com.codefollower.lealone.atomicdb.db.marshal.UUIDType;
import com.codefollower.lealone.atomicdb.dht.Token;
import com.codefollower.lealone.atomicdb.exceptions.WriteTimeoutException;
import com.codefollower.lealone.atomicdb.gms.FailureDetector;
import com.codefollower.lealone.atomicdb.io.sstable.Descriptor;
import com.codefollower.lealone.atomicdb.io.sstable.SSTableReader;
import com.codefollower.lealone.atomicdb.io.util.FastByteArrayOutputStream;
import com.codefollower.lealone.atomicdb.net.MessagingService;
import com.codefollower.lealone.atomicdb.service.StorageProxy;
import com.codefollower.lealone.atomicdb.service.StorageService;
import com.codefollower.lealone.atomicdb.service.WriteResponseHandler;
import com.codefollower.lealone.atomicdb.utils.ByteBufferUtil;
import com.codefollower.lealone.atomicdb.utils.FBUtilities;
import com.codefollower.lealone.atomicdb.utils.WrappedRunnable;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class BatchlogManager implements BatchlogManagerMBean
{
    private static final String MBEAN_NAME = "com.codefollower.lealone.atomicdb.db:type=BatchlogManager";
    private static final int VERSION = MessagingService.VERSION_12;
    private static final long TIMEOUT = 2 * DatabaseDescriptor.getWriteRpcTimeout();
    private static final long REPLAY_INTERVAL = 60 * 1000; // milliseconds

    private static final Logger logger = LoggerFactory.getLogger(BatchlogManager.class);
    public static final BatchlogManager instance = new BatchlogManager();

    private final AtomicLong totalBatchesReplayed = new AtomicLong();
    private final AtomicBoolean isReplaying = new AtomicBoolean();

    private static final ScheduledExecutorService batchlogTasks = new DebuggableScheduledThreadPoolExecutor("BatchlogTasks");
    
    public void start()
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(this, new ObjectName(MBEAN_NAME));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        Runnable runnable = new WrappedRunnable()
        {
            public void runMayThrow() throws ExecutionException, InterruptedException
            {
                replayAllFailedBatches();
            }
        };

        batchlogTasks.scheduleWithFixedDelay(runnable, StorageService.RING_DELAY, REPLAY_INTERVAL, TimeUnit.MILLISECONDS);
    }

    public int countAllBatches()
    {
        return (int) process("SELECT count(*) FROM %s.%s", Keyspace.SYSTEM_KS, SystemKeyspace.BATCHLOG_CF).one().getLong("count");
    }

    public long getTotalBatchesReplayed()
    {
        return totalBatchesReplayed.longValue();
    }

    public void forceBatchlogReplay()
    {
        Runnable runnable = new WrappedRunnable()
        {
            public void runMayThrow() throws ExecutionException, InterruptedException
            {
                replayAllFailedBatches();
            }
        };
        batchlogTasks.execute(runnable);
    }

    public static Mutation getBatchlogMutationFor(Collection<Mutation> mutations, UUID uuid)
    {
        long timestamp = FBUtilities.timestampMicros();
        ByteBuffer writtenAt = LongType.instance.decompose(timestamp / 1000);
        ByteBuffer data = serializeMutations(mutations);

        ColumnFamily cf = ArrayBackedSortedColumns.factory.create(CFMetaData.BatchlogCf);
        cf.addColumn(new Cell(cellName(""), ByteBufferUtil.EMPTY_BYTE_BUFFER, timestamp));
        cf.addColumn(new Cell(cellName("data"), data, timestamp));
        cf.addColumn(new Cell(cellName("written_at"), writtenAt, timestamp));

        return new Mutation(Keyspace.SYSTEM_KS, UUIDType.instance.decompose(uuid), cf);
    }

    private static ByteBuffer serializeMutations(Collection<Mutation> mutations)
    {
        FastByteArrayOutputStream bos = new FastByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);

        try
        {
            out.writeInt(mutations.size());
            for (Mutation mutation : mutations)
                Mutation.serializer.serialize(mutation, out, VERSION);
        }
        catch (IOException e)
        {
            throw new AssertionError(); // cannot happen.
        }

        return ByteBuffer.wrap(bos.toByteArray());
    }

    private void replayAllFailedBatches() throws ExecutionException, InterruptedException
    {
        if (!isReplaying.compareAndSet(false, true))
            return;

        logger.debug("Started replayAllFailedBatches");

        try
        {
            for (UntypedResultSet.Row row : process("SELECT id, written_at FROM %s.%s", Keyspace.SYSTEM_KS, SystemKeyspace.BATCHLOG_CF))
                if (System.currentTimeMillis() > row.getLong("written_at") + TIMEOUT)
                    replayBatch(row.getUUID("id"));
            cleanup();
        }
        finally
        {
            isReplaying.set(false);
        }

        logger.debug("Finished replayAllFailedBatches");
    }

    private void replayBatch(UUID id)
    {
        logger.debug("Replaying batch {}", id);

        UntypedResultSet result = process("SELECT written_at, data FROM %s.%s WHERE id = %s", Keyspace.SYSTEM_KS, SystemKeyspace.BATCHLOG_CF, id);
        if (result.isEmpty())
            return;

        try
        {
            UntypedResultSet.Row batch = result.one();
            replaySerializedMutations(batch.getBytes("data"), batch.getLong("written_at"));
        }
        catch (IOException e)
        {
            logger.warn("Skipped batch replay of {} due to {}", id, e);
        }

        process("DELETE FROM %s.%s WHERE id = %s", Keyspace.SYSTEM_KS, SystemKeyspace.BATCHLOG_CF, id);

        totalBatchesReplayed.incrementAndGet();
    }

    private void replaySerializedMutations(ByteBuffer data, long writtenAt) throws IOException
    {
        DataInputStream in = new DataInputStream(ByteBufferUtil.inputStream(data));
        int size = in.readInt();
        for (int i = 0; i < size; i++)
            replaySerializedMutation(Mutation.serializer.deserialize(in, VERSION), writtenAt);
    }

    /*
     * We try to deliver the mutations to the replicas ourselves if they are alive and only resort to writing hints
     * when a replica is down or a write request times out.
     */
    private void replaySerializedMutation(Mutation mutation, long writtenAt)
    {
        int ttl = calculateHintTTL(mutation, writtenAt);
        if (ttl <= 0)
            return; // the mutation isn't safe to replay.

        Set<InetAddress> liveEndpoints = new HashSet<InetAddress>();
        String ks = mutation.getKeyspaceName();
        Token<?> tk = StorageService.getPartitioner().getToken(mutation.key());
        for (InetAddress endpoint : Iterables.concat(StorageService.instance.getNaturalEndpoints(ks, tk),
                                                     StorageService.instance.getTokenMetadata().pendingEndpointsFor(tk, ks)))
        {
            if (endpoint.equals(FBUtilities.getBroadcastAddress()))
                mutation.apply();
            else if (FailureDetector.instance.isAlive(endpoint))
                liveEndpoints.add(endpoint); // will try delivering directly instead of writing a hint.
            else
                StorageProxy.writeHintForMutation(mutation, ttl, endpoint);
        }

        if (!liveEndpoints.isEmpty())
            attemptDirectDelivery(mutation, writtenAt, liveEndpoints);
    }

    private void attemptDirectDelivery(Mutation mutation, long writtenAt, Set<InetAddress> endpoints)
    {
        List<WriteResponseHandler> handlers = Lists.newArrayList();
        final CopyOnWriteArraySet<InetAddress> undelivered = new CopyOnWriteArraySet<InetAddress>(endpoints);
        for (final InetAddress ep : endpoints)
        {
            Runnable callback = new Runnable()
            {
                public void run()
                {
                    undelivered.remove(ep);
                }
            };
            WriteResponseHandler handler = new WriteResponseHandler(ep, WriteType.UNLOGGED_BATCH, callback);
            MessagingService.instance().sendRR(mutation.createMessage(), ep, handler);
            handlers.add(handler);
        }

        // Wait for all the requests to complete.
        for (WriteResponseHandler handler : handlers)
        {
            try
            {
                handler.get();
            }
            catch (WriteTimeoutException e)
            {
                logger.debug("Timed out replaying a batched mutation to a node, will write a hint");
            }
        }

        if (!undelivered.isEmpty())
        {
            int ttl = calculateHintTTL(mutation, writtenAt); // recalculate ttl
            if (ttl > 0)
                for (InetAddress endpoint : undelivered)
                    StorageProxy.writeHintForMutation(mutation, ttl, endpoint);
        }
    }

    // calculate ttl for the mutation's hint (and reduce ttl by the time the mutation spent in the batchlog).
    // this ensures that deletes aren't "undone" by an old batch replay.
    private int calculateHintTTL(Mutation mutation, long writtenAt)
    {
        return (int) ((HintedHandOffManager.calculateHintTTL(mutation) * 1000 - (System.currentTimeMillis() - writtenAt)) / 1000);
    }

    private static CellName cellName(String name)
    {
        return CFMetaData.BatchlogCf.comparator.makeCellName(name);
    }

    // force flush + compaction to reclaim space from the replayed batches
    private void cleanup() throws ExecutionException, InterruptedException
    {
        ColumnFamilyStore cfs = Keyspace.open(Keyspace.SYSTEM_KS).getColumnFamilyStore(SystemKeyspace.BATCHLOG_CF);
        cfs.forceBlockingFlush();
        Collection<Descriptor> descriptors = new ArrayList<Descriptor>();
        for (SSTableReader sstr : cfs.getSSTables())
            descriptors.add(sstr.descriptor);
        if (!descriptors.isEmpty()) // don't pollute the logs if there is nothing to compact.
            CompactionManager.instance.submitUserDefined(cfs, descriptors, Integer.MAX_VALUE).get();
    }

    private static UntypedResultSet process(String format, Object... args)
    {
        return QueryProcessor.processInternal(String.format(format, args));
    }
}
