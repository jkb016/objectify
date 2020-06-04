package com.googlecode.objectify.impl;

import com.google.common.collect.Iterables;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.Result;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages all the logic of deferring operations
 */
@RequiredArgsConstructor
public class Deferrer {

	/** */
	@Data
	abstract private static class Operation {
		private final ObjectifyOptions options;
	}

	@Data
	@EqualsAndHashCode(callSuper = true, doNotUseGetters = true)
	private static class DeleteOperation extends Operation {
		private final Key<?> key;

		public DeleteOperation(final ObjectifyOptions options, final Key<?> key) {
			super(options);
			this.key = key;
		}
	}

	@Data
	@EqualsAndHashCode(callSuper = true, doNotUseGetters = true)
	private static class SaveOperation extends Operation {
		private final Object entity;

		public SaveOperation(final ObjectifyOptions options, final Object entity) {
			super(options);
			this.entity = entity;
		}
	}

	/** */
	private final ObjectifyFactory factory;

	/** */
	private final Session session;

	/** */
	private final Map<Key<?>, Operation> operations = new HashMap<>();

	/** Entities with autogenerated (null) ids can't be put in the map, they don't have keys */
	private final List<SaveOperation> autogeneratedIdSaves = new ArrayList<>();

	/**
	 * Eliminate any deferred operations against the entity. Used when an explicit save (or delete) was
	 * executed against the key, so we no longer need the deferred operation.
	 *
	 * @param keyOrEntity can be a Key, Key<?>, Entity, or entity pojo
	 */
	public void undefer(final ObjectifyOptions options, final Object keyOrEntity) {
		if (keyOrEntity instanceof Key<?>) {
			operations.remove((Key<?>)keyOrEntity);
		}
		else if (keyOrEntity instanceof com.google.cloud.datastore.Key) {
			operations.remove(Key.create((com.google.cloud.datastore.Key)keyOrEntity));
		}
		else if (factory.keys().requiresAutogeneratedId(keyOrEntity)) {	// note might be FullEntity without complete key
			Iterables.removeIf(autogeneratedIdSaves, op -> Objects.equals(op.getOptions().getNamespace(), options.getNamespace()) && op.getEntity() == keyOrEntity);
		}
		else {
			final Key<?> key = factory.keys().keyOf(keyOrEntity, options.getNamespace());
			operations.remove(key);
		}
	}

	/**
	 */
	public void deferSave(final ObjectifyOptions options, final Object entity) {
		final SaveOperation saveOperation = new SaveOperation(options, entity);

		if (factory.keys().requiresAutogeneratedId(entity)) {
			autogeneratedIdSaves.add(saveOperation);
		} else {
			final Key<?> key = factory.keys().keyOf(entity, options.getNamespace());
			session.addValue(key, entity);
			operations.put(key, saveOperation);
		}
	}

	public void deferDelete(final ObjectifyOptions options, Key<?> key) {
		session.addValue(key, null);
		operations.put(key, new DeleteOperation(options, key));
	}

	public void flush(final ObjectifyImpl ofy) {
		final List<Result<?>> futures = new ArrayList<>();

		// Need to do this in a loop because @OnSave methods can enlist more deferred operations. Execution
		// of save or delete will undefer() all the relevant items, so both lists empty mean we're done.
		while (!operations.isEmpty() || !autogeneratedIdSaves.isEmpty()) {

			// First group by options
			final Map<ObjectifyOptions, List<Operation>> byOptions = Stream.concat(operations.values().stream(), autogeneratedIdSaves.stream()).collect(Collectors.groupingBy(Operation::getOptions));

			for (final Map.Entry<ObjectifyOptions, List<Operation>> entry : byOptions.entrySet()) {
				final ObjectifyOptions options = entry.getKey();
				final List<Operation> operationList = entry.getValue();

				// Sort into two batch operations: one for save, one for delete.
				final List<Object> saves = new ArrayList<>();
				final List<Key<?>> deletes = new ArrayList<>();

				for (final Operation operation : operationList) {
					if (operation instanceof SaveOperation) {
						saves.add(((SaveOperation)operation).getEntity());
					} else {
						deletes.add(((DeleteOperation)operation).getKey());
					}
				}

				if (!saves.isEmpty())
					futures.add(ofy.options(options).save().entities(saves));

				if (!deletes.isEmpty())
					futures.add(ofy.options(options).delete().keys(deletes));
			}
		}

		// Complete any pending operations
		for (final Result<?> future : futures) {
			future.now();
		}
	}
}
