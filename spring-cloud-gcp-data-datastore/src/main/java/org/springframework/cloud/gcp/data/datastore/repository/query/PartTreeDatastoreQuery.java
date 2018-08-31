/*
 *  Copyright 2018 original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.springframework.cloud.gcp.data.datastore.repository.query;

import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.google.cloud.datastore.Entity;
import com.google.cloud.datastore.StructuredQuery;
import com.google.cloud.datastore.StructuredQuery.Builder;
import com.google.cloud.datastore.StructuredQuery.CompositeFilter;
import com.google.cloud.datastore.StructuredQuery.Filter;
import com.google.cloud.datastore.StructuredQuery.OrderBy;
import com.google.cloud.datastore.StructuredQuery.PropertyFilter;

import org.springframework.cloud.gcp.data.datastore.core.DatastoreOperations;
import org.springframework.cloud.gcp.data.datastore.core.convert.DatastoreNativeTypes;
import org.springframework.cloud.gcp.data.datastore.core.mapping.DatastoreDataException;
import org.springframework.cloud.gcp.data.datastore.core.mapping.DatastoreMappingContext;
import org.springframework.cloud.gcp.data.datastore.core.mapping.DatastorePersistentEntity;
import org.springframework.cloud.gcp.data.datastore.core.mapping.DatastorePersistentProperty;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.repository.query.parser.Part;
import org.springframework.data.repository.query.parser.PartTree;
import org.springframework.data.repository.query.parser.PartTree.OrPart;

/**
 * Name-based query method for Cloud Datastore.
 *
 * @author Chengyuan Zhao
 *
 * @since 1.1
 */
public class PartTreeDatastoreQuery<T> extends AbstractDatastoreQuery<T> {

	private final PartTree tree;

	private final DatastorePersistentEntity datastorePersistentEntity;

	private final List<Part> filterParts;

	/**
	 * Constructor
	 * @param queryMethod the metadata for this query method.
	 * @param datastoreOperations used to execute the given query.
	 * @param datastoreMappingContext used to provide metadata for mapping results to
	 * objects.
	 * @param entityType the result domain type.
	 */
	PartTreeDatastoreQuery(QueryMethod queryMethod,
			DatastoreOperations datastoreOperations,
			DatastoreMappingContext datastoreMappingContext, Class<T> entityType) {
		super(queryMethod, datastoreOperations, datastoreMappingContext, entityType);
		this.tree = new PartTree(queryMethod.getName(), entityType);
		this.datastorePersistentEntity = this.datastoreMappingContext
				.getPersistentEntity(this.entityType);

		if (this.tree.isDelete()) {
			throw new UnsupportedOperationException(
					"Delete queries are not supported in Cloud Datastore: "
							+ this.queryMethod.getName());
		}
		else if (this.tree.isDistinct()) {
			throw new UnsupportedOperationException(
					"Cloud Datastore structured queries do not support the Distinct keyword.");
		}

		List orParts = this.tree.getParts().get().collect(Collectors.toList());
		if (orParts.size() != 1) {
			throw new DatastoreDataException(
					"Cloud Datastore only supports multiple filters combined with AND.");
		}
		this.filterParts = StreamSupport
				.stream(((OrPart) orParts.get(0)).spliterator(), false)
				.collect(Collectors.toList());
	}

	@Override
	public Object execute(Object[] parameters) {
		List<T> results = executeRawResult(parameters);
		if (this.tree.isCountProjection()) {
			return results.size();
		}
		else if (this.tree.isExistsProjection()) {
			return !results.isEmpty();
		}
		else {
			return applyProjection(results);
		}
	}

	@Override
	List<T> executeRawResult(Object[] parameters) {
		Iterable<T> found = this.datastoreOperations.query(getQuery(parameters),
				this.entityType);
		return found == null ? Collections.emptyList()
				: StreamSupport.stream(found.spliterator(), false)
						.collect(Collectors.toList());
	}

	private StructuredQuery<Entity> getQuery(Object[] parameters) {
		Builder<Entity> builder = StructuredQuery.newEntityQueryBuilder();

		builder.setKind(this.datastorePersistentEntity.kindName());

		if (this.tree.hasPredicate()) {
			builder.setFilter(getFilter(parameters));
		}

		if (!this.tree.getSort().isUnsorted()) {
			applySort(builder);
		}

		if (this.tree.isLimiting()) {
			builder.setLimit(this.tree.getMaxResults());
		}

		return builder.build();
	}

	private void applySort(Builder builder) {
		OrderBy[] orders = this.tree.getSort().get().map(sort -> {
			String fieldName = ((DatastorePersistentProperty) this.datastorePersistentEntity
					.getPersistentProperty(sort.getProperty())).getFieldName();
			return sort.isAscending() ? OrderBy.asc(fieldName) : OrderBy.desc(fieldName);
		}).toArray(OrderBy[]::new);
		if (orders.length > 1) {
			builder.setOrderBy(orders[0], Arrays.copyOfRange(orders, 1, orders.length));
		}
		else {
			builder.setOrderBy(orders[0]);
		}
	}

	private Filter getFilter(Object[] parameters) {
		Iterator it = Arrays.asList(parameters).iterator();
		Filter[] filters = this.filterParts.stream().map(part -> {
			Filter filter;
			String fieldName = ((DatastorePersistentProperty) this.datastorePersistentEntity
					.getPersistentProperty(part.getProperty().getSegment()))
							.getFieldName();
			try {
				switch (part.getType()) {
				case IS_NULL:
					filter = PropertyFilter.isNull(fieldName);
					break;
				case IS_EMPTY:
					filter = PropertyFilter.isNull(fieldName);
					break;
				case SIMPLE_PROPERTY:
					filter = PropertyFilter.eq(fieldName,
							DatastoreNativeTypes.wrapValue(it.next()));
					break;
				case GREATER_THAN_EQUAL:
					filter = PropertyFilter.ge(fieldName,
							DatastoreNativeTypes.wrapValue(it.next()));
					;
					break;
				case GREATER_THAN:
					filter = PropertyFilter.gt(fieldName,
							DatastoreNativeTypes.wrapValue(it.next()));
					;
					break;
				case LESS_THAN_EQUAL:
					filter = PropertyFilter.le(fieldName,
							DatastoreNativeTypes.wrapValue(it.next()));
					;
					break;
				case LESS_THAN:
					filter = PropertyFilter.lt(fieldName,
							DatastoreNativeTypes.wrapValue(it.next()));
					;
					break;
				default:
					throw new DatastoreDataException(
							"Only equals, greater-than-or-equals, greater-than, less-than-or-equals, "
									+ "less-than, and is-null are supported filters in Cloud Datastore.");

				}
				return filter;
			}
			catch (NoSuchElementException e) {
				throw new DatastoreDataException(
						"Too few parameters are provided for query method: "
								+ this.queryMethod.getName());
			}
		}).toArray(Filter[]::new);
		return filters.length > 1
				? CompositeFilter.and(filters[0],
						Arrays.copyOfRange(filters, 1, filters.length))
				: filters[0];
	}

}