package io.github.jutil.immutability.internal.analysis;

import com.sun.source.tree.Tree;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Finite may-origin domain shared by assignments, calls, returns and effects. */
final class ValueFacts {
    enum Kind { NULL, VALUE, RECEIVER, BORROWED, FRESH, SNAPSHOT, UNKNOWN }

    /** Identity is the allocation/entry site in one bounded call context. */
    static final class Origin {
        final Kind kind;
        final TypeMirror type;
        final Object initialization;
        final Set<CollectionProof> retained = new LinkedHashSet<CollectionProof>();
        final Map<Tree, String> hazards = new LinkedHashMap<Tree, String>();
        boolean unchecked;
        String description;

        Origin(Kind kind, TypeMirror type, Object initialization) {
            this.kind = kind;
            this.type = type;
            this.initialization = initialization;
        }
    }

    final Set<Origin> origins = new LinkedHashSet<Origin>();

    static ValueFacts of(Origin origin) {
        ValueFacts facts = new ValueFacts();
        facts.origins.add(origin);
        return facts;
    }

    ValueFacts union(ValueFacts other) {
        ValueFacts result = new ValueFacts();
        result.origins.addAll(origins);
        result.origins.addAll(other.origins);
        return result;
    }

    boolean has(Kind kind) {
        for (Origin origin : origins) {
            if (origin.kind == kind) {
                return true;
            }
        }
        return false;
    }

    static Map<Element, ValueFacts> join(Map<Element, ValueFacts> first,
                                         Map<Element, ValueFacts> second) {
        Map<Element, ValueFacts> result = new LinkedHashMap<Element, ValueFacts>(first);
        for (Map.Entry<Element, ValueFacts> entry : second.entrySet()) {
            ValueFacts old = result.get(entry.getKey());
            result.put(entry.getKey(), old == null ? entry.getValue() : old.union(entry.getValue()));
        }
        return result;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ValueFacts && origins.equals(((ValueFacts) other).origins);
    }

    @Override
    public int hashCode() {
        return origins.hashCode();
    }
}
