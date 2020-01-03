/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.index;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.helpers.collection.Visitor;
import org.neo4j.internal.kernel.api.PopulationProgress;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.kernel.api.exceptions.index.IndexPopulationFailedKernelException;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.schema.index.TestIndexDescriptorFactory;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.transaction.state.storeview.NeoStoreIndexStoreView;
import org.neo4j.lock.LockService;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobHandle;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.storageengine.api.EntityUpdates;
import org.neo4j.storageengine.api.IndexEntryUpdate;
import org.neo4j.storageengine.api.NodePropertyAccessor;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.test.scheduler.CallingThreadJobScheduler;
import org.neo4j.test.scheduler.JobSchedulerAdapter;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;
import org.neo4j.util.FeatureToggles;
import org.neo4j.values.storable.Values;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.neo4j.kernel.api.index.IndexQueryHelper.add;
import static org.neo4j.kernel.impl.api.index.BatchingMultipleIndexPopulator.AWAIT_TIMEOUT_MINUTES_NAME;
import static org.neo4j.kernel.impl.api.index.BatchingMultipleIndexPopulator.BATCH_SIZE_NAME;
import static org.neo4j.kernel.impl.api.index.IndexPopulationFailure.failure;
import static org.neo4j.kernel.impl.api.index.MultipleIndexPopulator.QUEUE_THRESHOLD_NAME;

public class BatchingMultipleIndexPopulatorTest
{
    public static final int propertyId = 1;
    public static final int labelId = 1;
    private final IndexDescriptor index1 = TestIndexDescriptorFactory.forLabel( 1, 1 );
    private final IndexDescriptor index42 = TestIndexDescriptorFactory.forLabel( 42, 42 );

    @AfterEach
    void tearDown()
    {
        clearProperty( QUEUE_THRESHOLD_NAME );
        clearProperty( AWAIT_TIMEOUT_MINUTES_NAME );
        clearProperty( BATCH_SIZE_NAME );
    }

    @Test
    void populateFromQueueDoesNothingIfThresholdNotReached() throws Exception
    {
        setProperty( QUEUE_THRESHOLD_NAME, 5 );

        BatchingMultipleIndexPopulator batchingPopulator = new BatchingMultipleIndexPopulator(
                mock( IndexStoreView.class ), NullLogProvider.getInstance(),
                mock( SchemaState.class ), mock( IndexStatisticsStore.class ), new CallingThreadJobScheduler() );

        IndexPopulator populator = addPopulator( batchingPopulator, index1 );
        IndexUpdater updater = mock( IndexUpdater.class );
        when( populator.newPopulatingUpdater( any() ) ).thenReturn( updater );

        IndexEntryUpdate<?> update1 = add( 1, index1.schema(), "foo" );
        IndexEntryUpdate<?> update2 = add( 2, index1.schema(), "bar" );
        batchingPopulator.queueConcurrentUpdate( update1 );
        batchingPopulator.queueConcurrentUpdate( update2 );

        batchingPopulator.applyConcurrentUpdateQueueBatched( 42 );

        verify( updater, never() ).process( any() );
        verify( populator, never() ).newPopulatingUpdater( any() );
    }

    @Test
    void populateFromQueuePopulatesWhenThresholdReached() throws Exception
    {
        setProperty( QUEUE_THRESHOLD_NAME, 2 );

        NeoStoreIndexStoreView storeView =
                new NeoStoreIndexStoreView( LockService.NO_LOCK_SERVICE, () -> mock( StorageReader.class ) );
        BatchingMultipleIndexPopulator batchingPopulator = new BatchingMultipleIndexPopulator(
                storeView, NullLogProvider.getInstance(), mock( SchemaState.class ), mock( IndexStatisticsStore.class ),
                new CallingThreadJobScheduler() );

        IndexPopulator populator1 = addPopulator( batchingPopulator, index1 );
        IndexUpdater updater1 = mock( IndexUpdater.class );
        when( populator1.newPopulatingUpdater( any() ) ).thenReturn( updater1 );

        IndexPopulator populator2 = addPopulator( batchingPopulator, index42 );
        IndexUpdater updater2 = mock( IndexUpdater.class );
        when( populator2.newPopulatingUpdater( any() ) ).thenReturn( updater2 );

        batchingPopulator.indexAllEntities();
        IndexEntryUpdate<?> update1 = add( 1, index1.schema(), "foo" );
        IndexEntryUpdate<?> update2 = add( 2, index42.schema(), "bar" );
        IndexEntryUpdate<?> update3 = add( 3, index1.schema(), "baz" );
        batchingPopulator.queueConcurrentUpdate( update1 );
        batchingPopulator.queueConcurrentUpdate( update2 );
        batchingPopulator.queueConcurrentUpdate( update3 );

        batchingPopulator.applyConcurrentUpdateQueueBatched( 42 );

        verify( updater1 ).process( update1 );
        verify( updater1 ).process( update3 );
        verify( updater2 ).process( update2 );
    }

    @Test
    void pendingBatchesFlushedAfterStoreScan() throws Exception
    {
        EntityUpdates update1 = nodeUpdates( 1, propertyId, "foo", labelId );
        EntityUpdates update2 = nodeUpdates( 2, propertyId, "bar", labelId );
        EntityUpdates update3 = nodeUpdates( 3, propertyId, "baz", labelId );
        EntityUpdates update42 = nodeUpdates( 4, 42, "42", 42 );
        IndexStoreView storeView = newStoreView( update1, update2, update3, update42 );

        BatchingMultipleIndexPopulator batchingPopulator = new BatchingMultipleIndexPopulator( storeView,
                NullLogProvider.getInstance(), mock( SchemaState.class ), mock( IndexStatisticsStore.class ),
                new CallingThreadJobScheduler() );

        IndexPopulator populator1 = addPopulator( batchingPopulator, index1 );
        IndexPopulator populator42 = addPopulator( batchingPopulator, index42 );

        batchingPopulator.indexAllEntities().run();

        verify( populator1 ).add( forUpdates( index1, update1, update2, update3 ) );
        verify( populator42 ).add( forUpdates( index42, update42 ) );
    }

    @Test
    void batchIsFlushedWhenThresholdReached() throws Exception
    {
        setProperty( BATCH_SIZE_NAME, 2 );

        EntityUpdates update1 = nodeUpdates( 1, propertyId, "foo", labelId );
        EntityUpdates update2 = nodeUpdates( 2, propertyId, "bar", labelId );
        EntityUpdates update3 = nodeUpdates( 3, propertyId, "baz", labelId );
        IndexStoreView storeView = newStoreView( update1, update2, update3 );

        BatchingMultipleIndexPopulator batchingPopulator = new BatchingMultipleIndexPopulator( storeView,
                NullLogProvider.getInstance(), mock( SchemaState.class ), mock( IndexStatisticsStore.class ),
                new CallingThreadJobScheduler() );

        IndexPopulator populator = addPopulator( batchingPopulator, index1 );

        batchingPopulator.indexAllEntities().run();

        verify( populator ).add( forUpdates( index1, update1, update2 ) );
        verify( populator ).add( forUpdates( index1, update3 ) );
    }

    @Test
    void populatorMarkedAsFailed() throws Exception
    {
        setProperty( BATCH_SIZE_NAME, 2 );

        EntityUpdates update1 = nodeUpdates( 1, propertyId, "aaa", labelId );
        EntityUpdates update2 = nodeUpdates( 1, propertyId, "bbb", labelId );
        IndexStoreView storeView = newStoreView( update1, update2 );

        RuntimeException batchFlushError = new RuntimeException( "Batch failed" );

        IndexPopulator populator;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ThreadPoolJobScheduler jobScheduler = new ThreadPoolJobScheduler( executor );
        try
        {
            BatchingMultipleIndexPopulator batchingPopulator = new BatchingMultipleIndexPopulator( storeView,
                    NullLogProvider.getInstance(), mock( SchemaState.class ), mock( IndexStatisticsStore.class ), jobScheduler );

            populator = addPopulator( batchingPopulator, index1 );
            List<IndexEntryUpdate<IndexDescriptor>> expected = forUpdates( index1, update1, update2 );
            doThrow( batchFlushError ).when( populator ).add( expected );

            batchingPopulator.indexAllEntities().run();
        }
        finally
        {
            jobScheduler.shutdown();
            executor.awaitTermination( 1, TimeUnit.MINUTES );
        }

        verify( populator ).markAsFailed( failure( batchFlushError ).asString() );
    }

    @Test
    void populatorMarkedAsFailedAndUpdatesNotAdded() throws Exception
    {
        setProperty( BATCH_SIZE_NAME, 2 );

        EntityUpdates update1 = nodeUpdates( 1, propertyId, "aaa", labelId );
        EntityUpdates update2 = nodeUpdates( 1, propertyId, "bbb", labelId );
        EntityUpdates update3 = nodeUpdates( 1, propertyId, "ccc", labelId );
        EntityUpdates update4 = nodeUpdates( 1, propertyId, "ddd", labelId );
        EntityUpdates update5 = nodeUpdates( 1, propertyId, "eee", labelId );
        IndexStoreView storeView = newStoreView( update1, update2, update3, update4, update5 );

        RuntimeException batchFlushError = new RuntimeException( "Batch failed" );

        BatchingMultipleIndexPopulator batchingPopulator = new BatchingMultipleIndexPopulator( storeView,
                NullLogProvider.getInstance(), mock( SchemaState.class ), mock( IndexStatisticsStore.class ),
                new CallingThreadJobScheduler() );

        IndexPopulator populator = addPopulator( batchingPopulator, index1 );
        doThrow( batchFlushError ).when( populator ).add( forUpdates( index1, update3, update4 ) );

        batchingPopulator.indexAllEntities().run();

        verify( populator ).add( forUpdates( index1, update1, update2 ) );
        verify( populator ).add( forUpdates( index1, update3, update4 ) );
        verify( populator ).markAsFailed( failure( batchFlushError ).asString() );
        verify( populator, never() ).add( forUpdates( index1, update5 ) );
    }

    @Test
    void shouldApplyBatchesInParallel() throws Exception
    {
        // given
        setProperty( BATCH_SIZE_NAME, 2 );
        EntityUpdates[] updates = new EntityUpdates[9];
        for ( int i = 0; i < updates.length; i++ )
        {
            updates[i] = nodeUpdates( i, propertyId, String.valueOf( i ), labelId );
        }
        IndexStoreView storeView = newStoreView( updates );
        AtomicInteger scheduleCount = new AtomicInteger();
        JobScheduler jobScheduler = new JobSchedulerAdapter()
        {
            @Override
            public JobHandle<?> schedule( Group group, Runnable job )
            {
                scheduleCount.incrementAndGet();
                job.run();
                return super.schedule( group, job );
            }
        };
        BatchingMultipleIndexPopulator batchingPopulator = new BatchingMultipleIndexPopulator( storeView,
                NullLogProvider.getInstance(), mock( SchemaState.class ), mock( IndexStatisticsStore.class ), jobScheduler );
        addPopulator( batchingPopulator, index1 );

        // when
        batchingPopulator.indexAllEntities().run();

        // then
        assertThat( scheduleCount.get() ).isGreaterThanOrEqualTo( 5 );
    }

    private List<IndexEntryUpdate<IndexDescriptor>> forUpdates( IndexDescriptor index, EntityUpdates... updates )
    {
        return Iterables.asList(
                Iterables.concat(
                        Iterables.map(
                                update -> update.forIndexKeys( Iterables.asIterable( index ) ),
                                Arrays.asList( updates )
                        ) ) );
    }

    private EntityUpdates nodeUpdates( int nodeId, int propertyId, String propertyValue, long...
            labelIds )
    {
        return EntityUpdates.forEntity( (long) nodeId, false ).withTokens( labelIds ).withTokensAfter( labelIds )
                .added( propertyId, Values.of( propertyValue ) )
                .build();
    }

    private static IndexPopulator addPopulator( BatchingMultipleIndexPopulator batchingPopulator, IndexDescriptor descriptor )
    {
        IndexPopulator populator = mock( IndexPopulator.class );

        IndexProxyFactory indexProxyFactory = mock( IndexProxyFactory.class );
        FailedIndexProxyFactory failedIndexProxyFactory = mock( FailedIndexProxyFactory.class );
        FlippableIndexProxy flipper = new FlippableIndexProxy();
        flipper.setFlipTarget( indexProxyFactory );

        batchingPopulator.addPopulator( populator,
                                        descriptor,
                                        flipper,
                                        failedIndexProxyFactory, "testIndex" );

        return populator;
    }

    private static IndexStoreView newStoreView( EntityUpdates... updates )
    {
        IndexStoreView storeView = mock( IndexStoreView.class );
        when( storeView.visitNodes( any(), any(), any(), any(), anyBoolean() ) ).thenAnswer( invocation ->
        {
            Visitor<EntityUpdates,IndexPopulationFailedKernelException> visitorArg = invocation.getArgument( 2 );
            return new IndexEntryUpdateScan( updates, visitorArg );
        } );
        when( storeView.newPropertyAccessor() ).thenReturn( mock( NodePropertyAccessor.class ) );
        return storeView;
    }

    private static ExecutorService sameThreadExecutor() throws InterruptedException
    {
        ExecutorService executor = immediateExecutor();
        when( executor.awaitTermination( anyLong(), any() ) ).thenReturn( true );
        doAnswer( invocation ->
        {
            ((Runnable) invocation.getArgument( 0 )).run();
            return null;
        } ).when( executor ).execute( any() );
        return executor;
    }

    private static void setProperty( String name, int value )
    {
        FeatureToggles.set( BatchingMultipleIndexPopulator.class, name, value );
    }

    private static void clearProperty( String name )
    {
        FeatureToggles.clear( BatchingMultipleIndexPopulator.class, name );
    }

    private static ExecutorService immediateExecutor()
    {
        ExecutorService result = mock( ExecutorService.class );
        doAnswer( invocation ->
        {
            invocation.<Runnable>getArgument( 0 ).run();
            return null;
        } ).when( result ).execute( any( Runnable.class ) );
        return result;
    }

    private static class IndexEntryUpdateScan implements StoreScan<IndexPopulationFailedKernelException>
    {
        final EntityUpdates[] updates;
        final Visitor<EntityUpdates,IndexPopulationFailedKernelException> visitor;

        boolean stop;

        IndexEntryUpdateScan( EntityUpdates[] updates,
                Visitor<EntityUpdates,IndexPopulationFailedKernelException> visitor )
        {
            this.updates = updates;
            this.visitor = visitor;
        }

        @Override
        public void run() throws IndexPopulationFailedKernelException
        {
            for ( EntityUpdates update : updates )
            {
                if ( stop )
                {
                    return;
                }
                visitor.visit( update );
            }
        }

        @Override
        public void stop()
        {
            stop = true;
        }

        @Override
        public void acceptUpdate( MultipleIndexPopulator.MultipleIndexUpdater updater, IndexEntryUpdate<?> update,
                long currentlyIndexedNodeId )
        {
        }

        @Override
        public PopulationProgress getProgress()
        {
            return PopulationProgress.NONE;
        }
    }
}
