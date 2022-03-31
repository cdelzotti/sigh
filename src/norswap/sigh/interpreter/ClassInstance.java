package norswap.sigh.interpreter;

import norswap.sigh.scopes.ClassScope;
import norswap.sigh.scopes.Scope;
import norswap.sigh.types.ClassType;
import norswap.sigh.types.Type;

import java.util.HashMap;
import java.util.Iterator;

public class ClassInstance {
    private final HashMap<String, Object> fields = new HashMap<>();
    private final ClassScope scope;
    private final ClassType type;

    public ClassInstance(ClassScope scope, ClassType type) {
        this.scope = scope;
        this.type = type;
    }

    public void set_field(String name, Object value) {
        fields.put(name, value);
    }

    public Object get_field(String name) {
        return fields.get(name);
    }

    public void refresh(ScopeStorage storage) {
        // Shouldn't call refresh on something else that a Class Scope storage.
        assert storage.scope instanceof ClassScope;
        Iterator<String> storageNames = storage.names();
        while (storageNames.hasNext()) {
            String name = storageNames.next();
            Object value = storage.get(storage.scope, name);
            fields.put(name, value);
        }
    }

    public HashMap<String, Object> fields() {
        return fields;
    }

    public ClassScope scope() {
        return scope;
    }

    public ClassType type() {
        return type;
    }
}
