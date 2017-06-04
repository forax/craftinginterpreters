package com.craftinginterpreters.lox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

// Warning, unlike the other Java file, IndyLox requires Java 8 !
public class IndyLox {
  static final Interpreter INTERPRETER;
  private static final Field LOXINSTANCE_KLASS = getField(LoxInstance.class, "klass");
  private static final Field INTERPRETER_LOCALS = getField(Interpreter.class, "locals");
  private static List<String> ARGS;
  
  static final ClassValue<LoxClass> CLASS_CACHE = new ClassValue<LoxClass>() {
    @Override
    protected LoxClass computeValue(Class<?> type) {
      Class<?> supertype = type.getSuperclass();
      LoxClass superClass = (supertype == null)? null: CLASS_CACHE.get(supertype);
      
      HashMap<String, LoxFunction> functionMap = new HashMap<>();
      functionMap.putAll(gatherFields(type, false));
      functionMap.putAll(gatherMethods(type, false));
      gatherInit(type).ifPresent(init -> functionMap.put("init", init));
      return new LoxClass(type.getName(), superClass, functionMap);
    }
  };
  
  static final ClassValue<LoxInstance> STATIC_CACHE = new ClassValue<LoxInstance>() {
    @Override
    protected LoxInstance computeValue(Class<?> type) {
      LoxClass loxClass = new LoxClass(type.getName() + "$static", null, gatherMethods(type, true));
      return new LoxInstance(loxClass);
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
    globals.define("import", asCallable(1, arguments -> CLASS_CACHE.get(forName((String) arguments.get(0)))));
    globals.define("static", asCallable(1, arguments -> STATIC_CACHE.get(toClass((LoxClass)arguments.get(0)))));
    globals.define("klass", asCallable(1, arguments -> getKlass((LoxInstance)arguments.get(0))));
    globals.define("wrap", asCallable(1, arguments -> wrap(arguments.get(0))));
    globals.define("unwrap", asCallable(1, arguments -> unwrap(arguments.get(0))));
    globals.define("unboxTo", asCallable(1, arguments -> unboxTo(arguments.get(0), toClass((LoxClass)arguments.get(1)))));
    globals.define("$bridge", asCallable(2, arguments -> {
      Member member = (Member)arguments.get(0);
      LoxInstance thiz = (LoxInstance)arguments.get(1);
      try {
        if (member instanceof Field) {
          Field field = (Field)member;
          return box(field.get(unwrap(thiz)));
        }
        
        Class<?>[] parameterTypes = ((Executable)member).getParameterTypes();
        Object[] args = IntStream.range(0, arguments.size() - 2).mapToObj(i -> unboxTo(arguments.get(i + 2), parameterTypes[i])).toArray();
        if (member instanceof Method) {
          Method method = (Method)member;
          return box(method.invoke(unwrap(thiz), args));
        }
        Constructor<?> constructor = (Constructor<?>)member;
        Object result = constructor.newInstance(args);
        thiz.fields.put("wrapped", result);
        return thiz;
      } catch(IllegalAccessException | InstantiationException | InvocationTargetException e) {
        throw new RuntimeError(token(""), e.getMessage());
      }
    }));
    
    globals.define("parse", asCallable(1, arguments -> {
      String filename = (String)arguments.get(0);
      Path path = Paths.get(filename);
      String source;
      try(Stream<String> lines = Files.lines(path)) {
        source = lines.collect(Collectors.joining("\n"));
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      
      Scanner scanner = new Scanner(source);
      List<Token> tokens = scanner.scanTokens();
      Parser parser = new Parser(tokens);
      return wrap(parser.parse());
    }));
    globals.define("ARGS", asCallable(0, __ -> wrap(ARGS)));
    
    INTERPRETER = interpreter;
  }
  
  private static Class<?> forName(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      throw new RuntimeError(token(""), e.getMessage());
    }
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
  
  
  
  static Map<String, LoxFunction> gatherMethods(Class<?> type, boolean isStatic) {
    Map<String, Method> methodMap =
        Arrays.stream(type.getMethods())
        .filter(m -> Modifier.isStatic(m.getModifiers()) == isStatic)
        .filter(IndyLox::isNotDeprecated)
        .collect(Collectors.toMap(Method::getName, Function.identity(), IndyLox::moreSpecific));
    return methodMap.entrySet().stream().collect(Collectors.toMap(e -> e.getKey(), e -> asFunction(e.getValue())));
  }
  
  static Map<String, LoxFunction> gatherFields(Class<?> type, boolean isStatic) {
    return Arrays.stream(type.getDeclaredFields())
        .filter(f -> Modifier.isStatic(f.getModifiers()) == isStatic)
        .peek(f -> f.setAccessible(true))
        .collect(Collectors.toMap(Field::getName, IndyLox::asFunction));
  }
  
  static Optional<LoxFunction> gatherInit(Class<?> type) {
    return Arrays.stream(type.getConstructors())
      .filter(IndyLox::isNotDeprecated)
      .reduce(IndyLox::moreSpecific)
      .map(IndyLox::asFunction);
  }
  
  private static boolean isNotDeprecated(Executable executable) {
    return !executable.isAnnotationPresent(Deprecated.class);
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
    
    Class<?> methodOwner = method.getDeclaringClass();
    Class<?> existingOwner = existing.getDeclaringClass();
    if (methodOwner.isAssignableFrom(existingOwner)) {
      return existing;
    }
    if (existingOwner.isAssignableFrom(methodOwner)) {
      return method;
    }
    
    // here it should be abstract methods with the same name and the same parameter types but
    // from two unrelated declaring class, just picking one should be ok.
    return methodOwner.getName().compareTo(existingOwner.getName()) > 0? method: existing;
  }

  static Token token(String name) {
    return new Token(TokenType.IDENTIFIER, name, null, -1);
  }
  private static Token keyword(TokenType type) {
    return new Token(type, type.name(), null, -1);
  }
  
  private static LoxFunction asFunction(Executable executable) {
    return asMember(executable, (member, parameterList, argList) -> {
      Parameter[] parameters = member.getParameters();
      for(Parameter parameter: parameters) {
        Token token = token(parameter.getName());
        parameterList.add(token);
        argList.add(new Expr.Variable(token));
      }  
    });
  }
  private static LoxFunction asFunction(Field field) {
    return asMember(field, (member, parameterList, argList) -> {  /* empty */ });
  }

  interface MemberConsumer<M> {
    void acept(M member, List<Token> parameterList, List<Expr> argList);
  }
  
  private static <M extends Member> LoxFunction asMember(M member, MemberConsumer<M> consumer) {
    ArrayList<Token> parameterList = new ArrayList<>();
    ArrayList<Expr> argList = new ArrayList<>();
    argList.add(new Expr.Literal(member));
    argList.add(new Expr.This(token("this")));
    consumer.acept(member, parameterList, argList);
    
    List<Stmt> body = Arrays.asList(
        new Stmt.Return(keyword(TokenType.RETURN), new Expr.Call(new Expr.Variable(token("$bridge")), keyword(TokenType.LEFT_PAREN), argList)));
    Stmt.Function declaration = new Stmt.Function(token(member.getName()), parameterList, body);
    
    Resolver resolver = new Resolver();
    Map<Expr, Integer> funLocals = resolver.resolve(
        Arrays.asList(new Stmt.Class(token(member.getDeclaringClass().getName()), null, Arrays.asList(declaration))));
    
    Map<Expr, Integer> locals = getLocals(INTERPRETER);
    locals.putAll(funLocals);
    
    return new LoxFunction(declaration, new Environment(), member instanceof Constructor);
  }
  
  static Object box(Object javaObject) {
    if (javaObject instanceof Double || javaObject instanceof String || javaObject instanceof Boolean ||
        javaObject instanceof LoxInstance || javaObject instanceof LoxFunction || javaObject instanceof LoxClass) {
      return javaObject;
    }
    if (javaObject instanceof Number) {
      return ((Number)javaObject).doubleValue();
    }
    if (javaObject instanceof Class) {
      return CLASS_CACHE.get((Class<?>)javaObject);
    }
    return wrap(javaObject);
  }
  static Object wrap(Object object) {
    if (object == null) {
      return null;
    }
    LoxClass loxClass = CLASS_CACHE.get(object.getClass());
    LoxInstance instance = new LoxInstance(loxClass);
    instance.fields.put("wrapped", object);
    return instance;
  }
  static Object unwrap(Object object) {
    if (object == null) {
      return null;
    }
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
      LoxFunction function = (LoxFunction)loxObject;
      if (type == Object.class) {
        return function;
      }
      if (type.isInterface()) {
        return lambdaProxy(function, type);
      }
      throw new RuntimeError(token(""), "can not convert a lox function " + function + " to a Java instance of " + type.getName());
    }
    if (loxObject instanceof LoxClass) {
      LoxClass klass = (LoxClass)loxObject;
      if (type == Object.class) {
        return klass;
      }
      if (klass.name.indexOf('.') == -1) {
        throw new RuntimeError(token(""), "can not convert a lox class to a Java instance of " + type.getName());
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
    if (loxObject instanceof Double && type==long.class) {
      return (long)(double)(Double)loxObject;
    }
    if (loxObject instanceof Double && type==float.class) {
      return (float)(double)(Double)loxObject;
    }
    return loxObject;
  }

  private static Object lambdaProxy(LoxFunction function, Class<?> type) {
    return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },  (proxy, method, args) -> {
      List<Object> arguments = Arrays.stream(args).map(arg -> box(arg)).collect(Collectors.toList());
      return unboxTo(function.call(INTERPRETER, arguments), method.getReturnType());
    });
  }
  
  private static Class<?> toClass(LoxClass loxClass) {
    return (Class<?>)unboxTo(loxClass, Class.class);
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
    ARGS = Arrays.stream(args).skip(1).collect(Collectors.toList());
    Lox.main(new String[] { args[0] });
  }
}
