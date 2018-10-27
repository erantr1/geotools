/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2010 - 2016, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.data.collection;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.geotools.feature.collection.SimpleFeatureCollection;
import org.geotools.feature.collection.SimpleFeatureIterator;
import org.geotools.data.util.NullProgressListener;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.filter.visitor.ExtractBoundsFilterVisitor;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.index.strtree.STRtree;
import org.opengis.feature.FeatureVisitor;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.sort.SortBy;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.util.ProgressListener;

/**
 * FeatureCollection used to stage information for display using a SpatialIndex.
 *
 * <p>Please note that this feature collection cannot be modified after the spatial index is
 * created.
 *
 * @author Jody
 */
public class SpatialIndexFeatureCollection implements SimpleFeatureCollection {

    static Logger LOGGER = Logging.getLogger(SpatialIndexFeatureCollection.class);

    /** SpatialIndex holding the contents of the FeatureCollection */
    protected STRtree index;

    protected SimpleFeatureType schema;

    public SpatialIndexFeatureCollection() {
        this.index = new STRtree();
    }

    public SpatialIndexFeatureCollection(SimpleFeatureType schema) {
        this.index = new STRtree();
        this.schema = schema;
    }

    public SpatialIndexFeatureCollection(SimpleFeatureCollection copy) throws IOException {
        this(copy.getSchema());

        addAll(copy);
    }

    @SuppressWarnings("unchecked")
    public SimpleFeatureIterator features() {
        Envelope everything =
                new Envelope(
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY);

        final List<SimpleFeature> list = (List<SimpleFeature>) index.query(everything);
        final Iterator<SimpleFeature> iterator = list.iterator();
        return new SimpleFeatureIterator() {
            public SimpleFeature next() throws NoSuchElementException {
                return iterator.next();
            }

            public boolean hasNext() {
                return iterator.hasNext();
            }

            public void close() {}
        };
    }

    public SimpleFeatureCollection sort(SortBy order) {
        throw new UnsupportedOperationException();
    }

    public SimpleFeatureCollection subCollection(Filter filter) {
        // split out the spatial part of the filter
        SpatialIndexFeatureCollection ret = new SpatialIndexFeatureCollection(schema);
        Envelope env = new Envelope();
        env = (Envelope) filter.accept(ExtractBoundsFilterVisitor.BOUNDS_VISITOR, env);
        if (LOGGER.isLoggable(Level.FINEST) && Double.isInfinite(env.getWidth())) {
            LOGGER.fine("Found no spatial element in " + filter);
            LOGGER.fine("Just going to iterate");
        }
        for (Iterator<SimpleFeature> iter = (Iterator<SimpleFeature>) index.query(env).iterator();
                iter.hasNext(); ) {

            SimpleFeature sample = iter.next();

            if (LOGGER.isLoggable(Level.FINEST)) {
                LOGGER.finest("Looking at " + sample);
            }
            if (filter.evaluate(sample)) {

                if (LOGGER.isLoggable(Level.FINEST)) {
                    LOGGER.finest("accepting " + sample);
                }
                ret.add(sample);
            }
        }

        return ret;
    }

    @Override
    public void accepts(final FeatureVisitor visitor, ProgressListener listener)
            throws IOException {
        Envelope everything =
                new Envelope(
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY);
        final ProgressListener progress = listener != null ? listener : new NullProgressListener();
        progress.started();
        final float size = (float) size();
        final IOException problem[] = new IOException[1];
        index.query(
                everything,
                new ItemVisitor() {
                    float count = 0f;

                    public void visitItem(Object item) {
                        SimpleFeature feature = null;
                        try {
                            feature = (SimpleFeature) item;
                            visitor.visit(feature);
                        } catch (Throwable t) {
                            progress.exceptionOccurred(t);
                            String fid =
                                    feature == null
                                            ? "feature"
                                            : feature.getIdentifier().toString();
                            problem[0] = new IOException("Problem visiting " + fid + ":" + t, t);
                        } finally {
                            progress.progress(count / size);
                        }
                    }
                });
        if (problem[0] != null) {
            throw problem[0];
        }
        progress.complete();
    }

    public boolean add(SimpleFeature feature) {
        ReferencedEnvelope bounds = ReferencedEnvelope.reference(feature.getBounds());
        index.insert(bounds, feature);

        return false;
    }

    public boolean addAll(Collection<? extends SimpleFeature> collection) {
        for (SimpleFeature feature : collection) {
            try {
                ReferencedEnvelope bounds = ReferencedEnvelope.reference(feature.getBounds());
                index.insert(bounds, feature);
            } catch (Throwable t) {
            }
        }
        return false;
    }

    public boolean addAll(
            FeatureCollection<? extends SimpleFeatureType, ? extends SimpleFeature> collection) {
        FeatureIterator<? extends SimpleFeature> iter = collection.features();
        try {
            while (iter.hasNext()) {
                try {
                    SimpleFeature feature = iter.next();
                    ReferencedEnvelope bounds = ReferencedEnvelope.reference(feature.getBounds());
                    index.insert(bounds, feature);
                } catch (Throwable t) {
                }
            }
        } finally {
            iter.close();
        }
        return false;
    }

    public synchronized void clear() {
        index = null;
        index = new STRtree();
    }

    public void close(FeatureIterator<SimpleFeature> close) {}

    public void close(Iterator<SimpleFeature> close) {}

    @SuppressWarnings("unchecked")
    public boolean contains(Object obj) {
        if (obj instanceof SimpleFeature) {
            SimpleFeature feature = (SimpleFeature) obj;
            ReferencedEnvelope bounds = ReferencedEnvelope.reference(feature.getBounds());
            for (Iterator<SimpleFeature> iter = (Iterator<SimpleFeature>) index.query(bounds);
                    iter.hasNext(); ) {
                SimpleFeature sample = iter.next();
                if (sample == feature) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean containsAll(Collection<?> collection) {
        boolean containsAll = true;
        for (Object obj : collection) {
            boolean contains = contains(obj);
            if (!contains) {
                containsAll = false;
                break;
            }
        }
        return containsAll;
    }

    public ReferencedEnvelope getBounds() {
        CoordinateReferenceSystem crs = schema.getCoordinateReferenceSystem();
        Envelope bounds = (Envelope) index.getRoot().getBounds();
        return new ReferencedEnvelope(bounds, crs);
    }

    public String getID() {
        return null;
    }

    public SimpleFeatureType getSchema() {
        return schema;
    }

    public boolean isEmpty() {
        return index.itemsTree().isEmpty();
    }

    @SuppressWarnings("unchecked")
    public Iterator<SimpleFeature> iterator() {
        Envelope everything =
                new Envelope(
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                        Double.POSITIVE_INFINITY);
        final List<SimpleFeature> list = (List<SimpleFeature>) index.query(everything);
        return (Iterator<SimpleFeature>) list.iterator();
    }

    public void purge() {}

    public boolean remove(Object o) {
        throw new UnsupportedOperationException("Cannot remove items from STRtree");
    }

    public boolean removeAll(Collection<?> c) {
        throw new UnsupportedOperationException("Cannot remove items from STRtree");
    }

    @SuppressWarnings("unchecked")
    public boolean retainAll(Collection<?> c) {
        throw new UnsupportedOperationException("Cannot remove items from STRtree");
    }

    /** Will build the STRtree index if required. */
    public int size() {
        return index.size();
    }

    public Object[] toArray() {
        return toArray(new Object[size()]);
    }

    @SuppressWarnings("unchecked")
    public <O> O[] toArray(O[] array) {
        int size = size();
        if (array.length < size) {
            array =
                    (O[])
                            java.lang.reflect.Array.newInstance(
                                    array.getClass().getComponentType(), size);
        }
        Iterator<SimpleFeature> it = iterator();
        try {
            Object[] result = array;
            for (int i = 0; i < size; i++) {
                result[i] = it.next();
            }
            if (array.length > size) {
                array[size] = null;
            }
            return array;
        } finally {
            close(it);
        }
    }
}