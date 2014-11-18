/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.unsafe.batchinsert;

import java.util.Comparator;
import java.util.SortedMap;
import java.util.TreeMap;

import org.neo4j.collection.pool.LinkedQueuePool;
import org.neo4j.function.Factory;
import org.neo4j.helpers.collection.IterableWrapper;
import org.neo4j.kernel.impl.store.AbstractRecordStore;
import org.neo4j.kernel.impl.store.record.AbstractBaseRecord;
import org.neo4j.kernel.impl.transaction.state.RecordAccess;
import org.neo4j.kernel.impl.util.statistics.IntCounter;

/**
 * Provides direct access to records in a store. Changes are batched up and written whenever {@link #commit()}
 * is called, or {@link #close()} for that matter.
 */
public class DirectRecordAccess<KEY extends Comparable<KEY>,RECORD extends AbstractBaseRecord,ADDITIONAL>
        implements RecordAccess<KEY,RECORD,ADDITIONAL>
{
    private final AbstractRecordStore<RECORD> store;
    private final Loader<KEY, RECORD, ADDITIONAL> loader;
    private final SortedMap<KEY,DirectRecordProxy> batch = new TreeMap<>( new Comparator<KEY>()
    {
        @Override
        public int compare( KEY o1, KEY o2 )
        {
            return -o1.compareTo( o2 );
        }
    });
    private final LinkedQueuePool<DirectRecordProxy> proxyFlyweightPool;
    private final IntCounter changeCounter = new IntCounter();

    public DirectRecordAccess( AbstractRecordStore<RECORD> store, Loader<KEY, RECORD, ADDITIONAL> loader )
    {
        this.store = store;
        this.loader = loader;
        // TODO: We should modify marshlandpool to support multiple items pooled per thread, and use that here.
        proxyFlyweightPool = new LinkedQueuePool<>( 100, new Factory<DirectRecordProxy>()
        {
            @Override
            public DirectRecordProxy newInstance()
            {
                return new DirectRecordProxy();
            }
        } );
    }

    @Override
    public RecordProxy<KEY, RECORD, ADDITIONAL> getOrLoad( KEY key, ADDITIONAL additionalData )
    {
        DirectRecordProxy loaded = batch.get( key );
        if ( loaded != null )
        {
            return loaded;
        }
        return putInBatch( key, proxy( key, loader.load( key, additionalData ), additionalData, false ) );
    }

    private RecordProxy<KEY, RECORD, ADDITIONAL> putInBatch( KEY key, DirectRecordProxy proxy )
    {
        DirectRecordProxy previous = batch.put( key, proxy );
        assert previous == null;
        return proxy;
    }

    @Override
    public RecordProxy<KEY, RECORD, ADDITIONAL> create( KEY key, ADDITIONAL additionalData )
    {
        return putInBatch( key, proxy( key, loader.newUnused( key, additionalData ), additionalData, true ) );
    }

    @Override
    public RecordProxy<KEY,RECORD,ADDITIONAL> getIfLoaded( KEY key )
    {
        return batch.get( key );
    }

    @Override
    public void setTo( KEY key, RECORD newRecord, ADDITIONAL additionalData )
    {
        throw new UnsupportedOperationException( "Not supported" );
    }

    @Override
    public int changeSize()
    {
        return changeCounter.value();
    }

    @Override
    public Iterable<RecordProxy<KEY,RECORD,ADDITIONAL>> changes()
    {
        return new IterableWrapper<RecordProxy<KEY,RECORD,ADDITIONAL>,DirectRecordProxy>(
                batch.values() )
        {
            @Override
            protected RecordProxy<KEY,RECORD,ADDITIONAL> underlyingObjectToObject( DirectRecordProxy object )
            {
                return object;
            }
        };
    }

    private DirectRecordProxy proxy( final KEY key, final RECORD record, final ADDITIONAL additionalData, boolean created )
    {
        DirectRecordProxy result = proxyFlyweightPool.acquire();
        result.bind( key, record, additionalData, created );
        return result;
    }

    private class DirectRecordProxy implements RecordProxy<KEY,RECORD,ADDITIONAL>
    {
        private KEY key;
        private RECORD record;
        private ADDITIONAL additionalData;
        private boolean changed;

        public void bind( KEY key, RECORD record, ADDITIONAL additionalData, boolean created )
        {
            this.changed = false;
            this.key = key;
            this.record = record;
            this.additionalData = additionalData;
            if ( created )
            {
                prepareChange();
            }
        }

        @Override
        public KEY getKey()
        {
            return key;
        }

        @Override
        public RECORD forChangingLinkage()
        {
            prepareChange();
            return record;
        }

        private void prepareChange()
        {
            if ( !changed )
            {
                changed = true;
                changeCounter.increment();
            }
        }

        @Override
        public RECORD forChangingData()
        {
            loader.ensureHeavy( record );
            prepareChange();
            return record;
        }

        @Override
        public RECORD forReadingLinkage()
        {
            return record;
        }

        @Override
        public RECORD forReadingData()
        {
            loader.ensureHeavy( record );
            return record;
        }

        @Override
        public ADDITIONAL getAdditionalData()
        {
            return additionalData;
        }

        @Override
        public RECORD getBefore()
        {
            return loader.load( key, additionalData );
        }

        @Override
        public String toString()
        {
            return record.toString();
        }

        public void store()
        {
            if ( changed )
            {
                store.updateRecord( record );
            }
        }

        @Override
        public boolean isChanged()
        {
            return changed;
        }
    }

    @Override
    public void close()
    {
        commit();
    }

    public void commit()
    {
        if ( changeCounter.value() == 0 )
        {
            return;
        }

        for ( DirectRecordProxy proxy : batch.values() )
        {
            proxy.store();
            proxyFlyweightPool.release( proxy );
        }
        changeCounter.clear();
        batch.clear();
    }
}
