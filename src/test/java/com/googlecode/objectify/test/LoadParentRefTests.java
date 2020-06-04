/*
 */

package com.googlecode.objectify.test;

import com.googlecode.objectify.Key;
import com.googlecode.objectify.LoadResult;
import com.googlecode.objectify.Ref;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Load;
import com.googlecode.objectify.annotation.Parent;
import com.googlecode.objectify.test.util.TestBase;
import lombok.Data;
import org.junit.jupiter.api.Test;

import static com.google.common.truth.Truth.assertThat;
import static com.googlecode.objectify.ObjectifyService.factory;
import static com.googlecode.objectify.ObjectifyService.ofy;

/**
 * Tests the fetching system for parent values using Ref<?> holders.
 *
 * @author Jeff Schnitzer <jeff@infohazard.org>
 */
class LoadParentRefTests extends TestBase {
	/** */
	@Entity
	@Data
	private static class Father {
		@Id Long id;
		String foo;
	}

	/** */
	@Entity
	@Data
	private static class Child {
		@Id Long id;
		@Load @Parent Ref<Father> father;
		String bar;
	}

	/** Clears the session to get a full load */
	@Test
	void testParentExists() throws Exception {
		factory().register(Father.class);
		factory().register(Child.class);

		final Father f = new Father();
		f.foo = "foo";
		ofy().save().entity(f).now();

		final Child ch = new Child();
		ch.father = Ref.create(Key.create(f));
		ch.bar = "bar";
		ofy().save().entity(ch).now();

		ofy().clear();

		final LoadResult<Child> fetchedRef = ofy().load().key(Key.create(ch));
		final Child fetched = fetchedRef.now();

		assertThat(fetched.bar).isEqualTo(ch.bar);
		assertThat(fetched.father.get()).isEqualTo(f);
	}

	/** */
	@Entity
	@Data
	private static class TreeNode {
		@Id Long id;
		@Load @Parent Ref<TreeNode> parent;
		String foo;
	}

	/** */
	@Test
	void testTwoLevelsOfFetch() throws Exception {
		factory().register(TreeNode.class);

		final TreeNode node1 = new TreeNode();
		node1.foo = "foo1";
		ofy().save().entity(node1).now();

		final TreeNode node2 = new TreeNode();
		node2.parent = Ref.create(node1);
		node2.foo = "foo2";
		ofy().save().entity(node2).now();

		final TreeNode node3 = new TreeNode();
		node3.parent = Ref.create(node2);
		node3.foo = "foo3";
		ofy().save().entity(node3).now();

		ofy().clear();

		final TreeNode fetched3 = ofy().load().entity(node3).now();

		assertThat(fetched3.foo).isEqualTo(node3.foo);
		assertThat(fetched3.parent.get()).isEqualTo(node2);
		assertThat(fetched3.parent.get().parent.get()).isEqualTo(node1);
		assertThat(fetched3.parent.get().parent.get().parent).isNull();
	}

	/** */
	@Test
	void testMissingIntermediate() throws Exception {
		factory().register(TreeNode.class);

		final TreeNode node1 = new TreeNode();
		node1.foo = "foo1";
		final Key<TreeNode> key1 = ofy().save().entity(node1).now();

		// Node2 should not exist but should have a concrete id for node3
		final TreeNode node2 = new TreeNode();
		node2.id = 999L;
		node2.parent = Ref.create(key1);
		final Key<TreeNode> key2 = Key.create(node2);

		final TreeNode node3 = new TreeNode();
		node3.parent = Ref.create(key2);
		node3.foo = "foo3";
		final Key<TreeNode> key3 = ofy().save().entity(node3).now();

		ofy().clear();

		// Doing this step by step to make it easier for debugging
		final LoadResult<TreeNode> fetched3Ref = ofy().load().key(key3);
		final TreeNode fetched3 = fetched3Ref.now();

		assertThat(fetched3.parent.get()).isNull();
		assertThat(fetched3.parent.key()).isEqualTo(key2);
		assertThat(fetched3.parent.key().getParent()).isEqualTo(key1);
	}

	/** */
	@Entity
	@Data
	private static class ChildWithGroup {
		static class Group {}

		@Id Long id;
		@Load(Group.class) @Parent Ref<Father> father;
		String bar;
	}

	/** */
	@Test
	void testParentWithGroup() throws Exception {
		factory().register(Father.class);
		factory().register(ChildWithGroup.class);

		final Father f = new Father();
		f.foo = "foo";
		ofy().save().entity(f).now();

		final ChildWithGroup ch = new ChildWithGroup();
		ch.father = Ref.create(Key.create(f));
		ch.bar = "bar";
		final Key<ChildWithGroup> kch = ofy().save().entity(ch).now();

		// This should get an empty ref
		ofy().clear();
		final ChildWithGroup fetched = ofy().load().key(kch).now();
		assertThat(fetched.father.key().getId()).isEqualTo(f.id);
		assertThat(fetched.father.isLoaded()).isFalse();

		// Upgrade in the same session
		final ChildWithGroup fetched2 = ofy().load().group(ChildWithGroup.Group.class).key(kch).now();
		assertThat(fetched2).isSameInstanceAs(fetched);
		assertThat(fetched2.father.isLoaded()).isTrue();
		assertThat(fetched2.father.get()).isEqualTo(f);

		// Also should work after session is cleared, but objects will not be same
		ofy().clear();
		final ChildWithGroup fetched3 = ofy().load().group(ChildWithGroup.Group.class).key(kch).now();
		assertThat(fetched3).isNotSameInstanceAs(fetched2);
		assertThat(fetched3.father.isLoaded()).isTrue();
		assertThat(fetched3.father.get()).isEqualTo(f);
	}
}