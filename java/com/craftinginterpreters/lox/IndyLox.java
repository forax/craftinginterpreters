package com.craftinginterpreters.lox;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

// Warning, unlike the other Java file, IndyLox requires Java 8 !
public class IndyLox {
  private static final Interpreter INTERPRETER;
  private static final Field LOXINSTANCE_KLASS = getField(LoxInstance.class, "klass");
  private static final Field INTERPRETER_LOCALS = getField(Interpreter.class, "locals");
  
  static final ClassValue<LoxClass> CACHE = new ClassValue<LoxClass>() {
    @Override
    protected LoxClass computeValue(Class<?> type) {
      Class<?> supertype = type.getSuperclass();
      LoxClass superClass = (supertype == null)? null: CACHE.get(supertype);
      return new LoxClass(type.getName(), superClass, gatherMethods(type));
    }
  };
  
  static {
    Field interpreterField = getField(Lox.class, "interpreter");
    Interpreter interpreter;
    try {
      interpreter = (Interpreter)interpreterField.get(null);
    } catch (IllegalAccessException e) {
      throw new AssertionError(e);
    }
    
    Environment globals = interpreter.globals;
    globals.define("import", asCallable(1, arguments -> {
      try {
        return CACHE.get(Class.forName((String) arguments.get(0)));
      } catch (ClassNotFoundException e) {
        throw new RuntimeError(token(""), e.getMessage());
      }
    }));
    globals.define("wrap", asCallable(1, arguments -> wrap(arguments.get(0))));
    globals.define("unwrap", asCallable(1, arguments -> unwrap(arguments.get(0))));
    IntStream.range(0, 20).forEach(parameterCount -> globals.define("$bridge" + parameterCount, asCallable(parameterCount, arguments -> {
      Executable executable = (Executable)arguments.get(0);
      Class<?>[] parameterTypes = executable.getParameterTypes();
      Object thiz = unboxTo(arguments.get(1), executable.getDeclaringClass());
      Object[] args = IntStream.range(0, arguments.size() - 2).mapToObj(i -> unboxTo(arguments.get(i + 2), parameterTypes[i])).toArray();
      try {
        if (executable instanceof Method) {
          Method method = (Method)executable;
          return box(method.invoke(thiz, args));
        }
        Constructor<?> constructor = (Constructor<?>)executable;
        return box(constructor.newInstance(thiz, args));
      } catch(IllegalAccessException | InstantiationException | InvocationTargetException e) {
        throw new RuntimeError(token(""), e.getMessage());
      }
    })));
    
    INTERPRETER = interpreter;
  }
  
  private static Callable asCallable(int parameterCount, Function<List<Object>, Object> fun) {
    return new Callable() {
      @Override
      public int requiredArguments() {
        return parameterCount;
      }
      
      @Override
      public Object call(Interpreter interpreter, List<Object> arguments) {
        return fun.apply(arguments);
      }
    };
  }
  
  
  
  static Map<String, LoxFunction> gatherMethods(Class<?> type) {
    Map<String, Method> methodMap =
        Arrays.stream(type.getMethods())
        .filter(m -> !Modifier.isStatic(m.getModifiers()))
        .collect(Collectors.toMap(Method::getName, Function.identity(), IndyLox::moreSpecific));
    Map<String, LoxFunction> functionMap = methodMap.entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> asFunction(e.getValue())));
    Arrays.stream(type.getConstructors()).reduce(IndyLox::moreSpecific).map(IndyLox::asFunction).ifPresent(init -> functionMap.put("init", init));
    return functionMap;
  }
  
  private static <E extends Executable> E moreSpecific(E method, E existing) {
    if (existing.getParameterCount() > method.getParameterCount()) {
      return existing;
    }
    if (existing.getParameterCount() < method.getParameterCount()) {
      return method;
    }
    
    Class<?>[] existingTypes = existing.getParameterTypes();
    Class<?>[] methodTypes = method.getParameterTypes();
    for(int i = 0; i < existingTypes.length; i++) {
      Class<?> methodType = methodTypes[i];
      Class<?> existingType = existingTypes[i];
      if (methodType.isAssignableFrom(existingType)) {
        return existing;
      }
      if (existingType.isAssignableFrom(methodType)) {
        return method;
      }
      if (methodType == existingType) {
        continue;
      }
      // type are not comparable, choose by lexicographic order of their name, at least this is a stable order
      return methodType.getName().compareTo(existingType.getName()) > 0? method: existing;
    }
    throw new AssertionError("same name, same parameter types ??");
  }

  static Token token(String name) {
    return new Token(TokenType.IDENTIFIER, name, null, -1);
  }
  private static Token keyword(TokenType type) {
    return new Token(type, type.name(), null, -1);
  }
  
  private static LoxFunction asFunction(Executable executable) {
    ArrayList<Token> parameterList = new ArrayList<>();
    ArrayList<Expr> argList = new ArrayList<>();
    argList.add(new Expr.Literal(executable));
    argList.add(new Expr.This(token("this")));
    
    Parameter[] parameters = executable.getParameters();
    for(Parameter parameter: parameters) {
      Token token = token(parameter.getName());
      parameterList.add(token);
      argList.add(new Expr.Variable(token));
    }
    
    List<Stmt> body = Arrays.asList(
        new Stmt.Return(keyword(TokenType.RETURN), new Expr.Call(new Expr.Variable(token("$bridge" + executable.getParameterCount())), keyword(TokenType.LEFT_PAREN), argList)));
    Stmt.Function declaration = new Stmt.Function(token(executable.getName()), parameterList, body);
    
    Resolver resolver = new Resolver();
    Map<Expr, Integer> funLocals = resolver.resolve(
        Arrays.asList(new Stmt.Class(token(executable.getDeclaringClass().getName()), null, Arrays.asList(declaration))));
    
    Map<Expr, Integer> locals = getLocals(INTERPRETER);
    locals.putAll(funLocals);
    
    return new LoxFunction(declaration, new Environment(), false /* FIXME */);
  }

  static Object box(Object javaObject) {
    if (javaObject instanceof Double || javaObject instanceof String || javaObject instanceof Boolean) {
      return javaObject;
    }
    if (javaObject instanceof Class) {
      return CACHE.get((Class<?>)javaObject);
    }
    return wrap(javaObject);
  }
  static Object wrap(Object object) {
    LoxClass loxClass = CACHE.get(object.getClass());
    LoxInstance instance = new LoxInstance(loxClass);
    instance.fields.put("wrapped", object);
    return instance;
  }
  static Object unwrap(Object object) {
    LoxInstance instance = (LoxInstance)object;
    LoxClass klass = getKlass(instance);
    if (klass.name.indexOf('.') == -1) {
      throw new RuntimeError(token(""), "can not convert a lox instance to a Java instance");
    }
    return instance.fields.get("wrapped");
  }
  static Object unboxTo(Object loxObject, Class<?> type) {
    if (loxObject instanceof LoxInstance) {
      return unwrap(loxObject);
    }
    if (loxObject instanceof LoxFunction) {
      throw new RuntimeError(token(""), "can not convert a lox function to a Java instance");
    }
    if (loxObject instanceof LoxClass) {
      LoxClass klass = (LoxClass)loxObject;
      if (klass.name.indexOf('.') == -1) {
        throw new RuntimeError(token(""), "can not convert a lox class to a Java instance");
      }
      try {
        return Class.forName(klass.name);
      } catch (ClassNotFoundException e) {
        throw new RuntimeError(token(""), "can not convert a lox class to a Java instance: " + e.getMessage());
      }
    }
    if (loxObject instanceof Double && type==int.class) {
      return (int)(double)(Double)loxObject;
    }
    return loxObject;
  }
  
  private static Field getField(Class<?> clazz, String name) {
    Field field;
    try {
      field = clazz.getDeclaredField(name);
    } catch (NoSuchFieldException e) {
      throw new AssertionError(e);
    }
    field.setAccessible(true);
    return field;
  }
  
  private static LoxClass getKlass(LoxInstance instance) {
    try {
      return (LoxClass)LOXINSTANCE_KLASS.get(instance);
    } catch (IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
  @SuppressWarnings("unchecked")
  private static Map<Expr, Integer> getLocals(Interpreter interpreter) {
    try {
      return (Map<Expr, Integer>)INTERPRETER_LOCALS.get(interpreter);
    } catch (IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }
  
  public static void main(String[] args) throws IOException {
    Lox.main(args);
  }
}
