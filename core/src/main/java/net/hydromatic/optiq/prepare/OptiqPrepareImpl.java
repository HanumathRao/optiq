/*
// Licensed to Julian Hyde under one or more contributor license
// agreements. See the NOTICE file distributed with this work for
// additional information regarding copyright ownership.
//
// Julian Hyde licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except in
// compliance with the License. You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
*/
package net.hydromatic.optiq.prepare;

import net.hydromatic.avatica.AvaticaParameter;
import net.hydromatic.avatica.ColumnMetaData;
import net.hydromatic.avatica.Helper;

import net.hydromatic.linq4j.*;
import net.hydromatic.linq4j.expressions.*;
import net.hydromatic.linq4j.function.Function1;

import net.hydromatic.optiq.*;
import net.hydromatic.optiq.config.OptiqConnectionConfig;
import net.hydromatic.optiq.impl.java.JavaTypeFactory;
import net.hydromatic.optiq.jdbc.OptiqPrepare;
import net.hydromatic.optiq.jdbc.OptiqSchema;
import net.hydromatic.optiq.materialize.MaterializationService;
import net.hydromatic.optiq.rules.java.*;
import net.hydromatic.optiq.runtime.*;
import net.hydromatic.optiq.server.OptiqServerStatement;
import net.hydromatic.optiq.tools.Frameworks;

import org.eigenbase.rel.*;
import org.eigenbase.rel.rules.*;
import org.eigenbase.relopt.*;
import org.eigenbase.relopt.volcano.VolcanoPlanner;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.RexBuilder;
import org.eigenbase.rex.RexNode;
import org.eigenbase.sql.*;
import org.eigenbase.sql.fun.SqlStdOperatorTable;
import org.eigenbase.sql.parser.SqlParseException;
import org.eigenbase.sql.parser.SqlParser;
import org.eigenbase.sql.parser.impl.SqlParserImpl;
import org.eigenbase.sql.type.*;
import org.eigenbase.sql.util.ChainedSqlOperatorTable;
import org.eigenbase.sql.validate.*;
import org.eigenbase.sql2rel.SqlToRelConverter;
import org.eigenbase.sql2rel.StandardConvertletTable;
import org.eigenbase.util.Util;

import com.google.common.collect.*;

import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.*;
import org.codehaus.janino.Scanner;

import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.*;

/**
 * Shit just got real.
 *
 * <p>This class is public so that projects that create their own JDBC driver
 * and server can fine-tune preferences. However, this class and its methods are
 * subject to change without notice.</p>
 */
public class OptiqPrepareImpl implements OptiqPrepare {

  public static final boolean DEBUG =
      "true".equals(System.getProperties().getProperty("optiq.debug"));

  /** Whether to enable the collation trait. Some extra optimizations are
   * possible if enabled, but queries should work either way. At some point
   * this will become a preference, or we will run multiple phases: first
   * disabled, then enabled. */
  private static final boolean ENABLE_COLLATION_TRAIT = true;

  private static final Set<String> SIMPLE_SQLS =
      ImmutableSet.of(
          "SELECT 1",
          "select 1",
          "SELECT 1 FROM DUAL",
          "select 1 from dual",
          "values 1",
          "VALUES 1");

  public OptiqPrepareImpl() {
  }

  public ParseResult parse(
      Context context, String sql) {
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    OptiqCatalogReader catalogReader =
        new OptiqCatalogReader(
            context.getRootSchema(),
            context.config().caseSensitive(),
            context.getDefaultSchemaPath(),
            typeFactory);
    SqlParser parser = SqlParser.create(sql);
    SqlNode sqlNode;
    try {
      sqlNode = parser.parseStmt();
    } catch (SqlParseException e) {
      throw new RuntimeException("parse failed", e);
    }
    final SqlValidator validator =
        new OptiqSqlValidator(
            SqlStdOperatorTable.instance(), catalogReader, typeFactory);
    SqlNode sqlNode1 = validator.validate(sqlNode);
    return new ParseResult(this, validator, sql, sqlNode1,
        validator.getValidatedNodeType(sqlNode1));
  }

  /** Creates a collection of planner factories.
   *
   * <p>The collection must have at least one factory, and each factory must
   * create a planner. If the collection has more than one planner, Optiq will
   * try each planner in turn.</p>
   *
   * <p>One of the things you can do with this mechanism is to try a simpler,
   * faster, planner with a smaller rule set first, then fall back to a more
   * complex planner for complex and costly queries.</p>
   *
   * <p>The default implementation returns a factory that calls
   * {@link #createPlanner(net.hydromatic.optiq.jdbc.OptiqPrepare.Context)}.</p>
   */
  protected List<Function1<Context, RelOptPlanner>> createPlannerFactories() {
    return Collections.<Function1<Context, RelOptPlanner>>singletonList(
        new Function1<Context, RelOptPlanner>() {
          public RelOptPlanner apply(Context context) {
            return createPlanner(context);
          }
        });
  }

  /** Creates a query planner and initializes it with a default set of
   * rules. */
  protected RelOptPlanner createPlanner(Context context) {
    final VolcanoPlanner planner = new VolcanoPlanner();
    planner.addRelTraitDef(ConventionTraitDef.INSTANCE);
    if (ENABLE_COLLATION_TRAIT) {
      planner.addRelTraitDef(RelCollationTraitDef.INSTANCE);
      planner.registerAbstractRelationalRules();
    }
    RelOptUtil.registerAbstractRels(planner);
    planner.addRule(JavaRules.ENUMERABLE_JOIN_RULE);
    planner.addRule(JavaRules.ENUMERABLE_PROJECT_RULE);
    planner.addRule(JavaRules.ENUMERABLE_FILTER_RULE);
    planner.addRule(JavaRules.ENUMERABLE_AGGREGATE_RULE);
    planner.addRule(JavaRules.ENUMERABLE_SORT_RULE);
    planner.addRule(JavaRules.ENUMERABLE_LIMIT_RULE);
    planner.addRule(JavaRules.ENUMERABLE_UNION_RULE);
    planner.addRule(JavaRules.ENUMERABLE_INTERSECT_RULE);
    planner.addRule(JavaRules.ENUMERABLE_MINUS_RULE);
    planner.addRule(JavaRules.ENUMERABLE_TABLE_MODIFICATION_RULE);
    planner.addRule(JavaRules.ENUMERABLE_VALUES_RULE);
    planner.addRule(JavaRules.ENUMERABLE_WINDOW_RULE);
    planner.addRule(JavaRules.ENUMERABLE_ONE_ROW_RULE);
    planner.addRule(JavaRules.ENUMERABLE_EMPTY_RULE);
    planner.addRule(TableAccessRule.INSTANCE);
    planner.addRule(MergeProjectRule.INSTANCE);
    planner.addRule(PushFilterPastProjectRule.INSTANCE);
    planner.addRule(PushFilterPastJoinRule.FILTER_ON_JOIN);
    planner.addRule(RemoveDistinctAggregateRule.INSTANCE);
    planner.addRule(ReduceAggregatesRule.INSTANCE);
    planner.addRule(SwapJoinRule.INSTANCE);
    planner.addRule(PushJoinThroughJoinRule.RIGHT);
    planner.addRule(PushJoinThroughJoinRule.LEFT);
    planner.addRule(PushSortPastProjectRule.INSTANCE);

    // Change the below to enable constant-reduction.
    if (false) {
      planner.addRule(ReduceExpressionsRule.PROJECT_INSTANCE);
      planner.addRule(ReduceExpressionsRule.FILTER_INSTANCE);
      planner.addRule(ReduceExpressionsRule.CALC_INSTANCE);
      planner.addRule(ReduceExpressionsRule.JOIN_INSTANCE);
      planner.addRule(ReduceValuesRule.FILTER_INSTANCE);
      planner.addRule(ReduceValuesRule.PROJECT_FILTER_INSTANCE);
      planner.addRule(ReduceValuesRule.PROJECT_INSTANCE);
    }

    final SparkHandler spark = context.spark();
    if (spark.enabled()) {
      spark.registerRules(planner);
    }
    return planner;
  }

  public <T> PrepareResult<T> prepareQueryable(
      Context context,
      Queryable<T> queryable) {
    return prepare_(context, null, queryable, queryable.getElementType(), -1);
  }

  public <T> PrepareResult<T> prepareSql(
      Context context,
      String sql,
      Queryable<T> expression,
      Type elementType,
      int maxRowCount) {
    return prepare_(context, sql, expression, elementType, maxRowCount);
  }

  <T> PrepareResult<T> prepare_(
      Context context,
      String sql,
      Queryable<T> queryable,
      Type elementType,
      int maxRowCount) {
    if (SIMPLE_SQLS.contains(sql)) {
      return simplePrepare(context, sql);
    }
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    OptiqCatalogReader catalogReader =
        new OptiqCatalogReader(
            context.getRootSchema(),
            context.config().caseSensitive(),
            context.getDefaultSchemaPath(),
            typeFactory);
    final List<Function1<Context, RelOptPlanner>> plannerFactories =
        createPlannerFactories();
    if (plannerFactories.isEmpty()) {
      throw new AssertionError("no planner factories");
    }
    RuntimeException exception = new RuntimeException();
    for (Function1<Context, RelOptPlanner> plannerFactory : plannerFactories) {
      final RelOptPlanner planner = plannerFactory.apply(context);
      if (planner == null) {
        throw new AssertionError("factory returned null planner");
      }
      try {
        return prepare2_(
            context, sql, queryable, elementType, maxRowCount,
            catalogReader, planner);
      } catch (RelOptPlanner.CannotPlanException e) {
        exception = e;
      }
    }
    throw exception;
  }

  /** Quickly prepares a simple SQL statement, circumventing the usual
   * preparation process. */
  private <T> PrepareResult<T> simplePrepare(Context context, String sql) {
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    final RelDataType x =
        typeFactory.builder().add("EXPR$0", SqlTypeName.INTEGER).build();
    @SuppressWarnings("unchecked")
    final List<T> list = (List) ImmutableList.of(1);
    final List<String> origin = null;
    final List<List<String>> origins =
        Collections.nCopies(x.getFieldCount(), origin);
    return new PrepareResult<T>(
        sql,
        ImmutableList.<AvaticaParameter>of(),
        x,
        getColumnMetaDataList(typeFactory, x, x, origins),
        -1,
        new Bindable<T>() {
          public Enumerable<T> bind(DataContext dataContext) {
            return Linq4j.asEnumerable(list);
          }
        },
        Integer.class);
  }

  <T> PrepareResult<T> prepare2_(
      Context context,
      String sql,
      Queryable<T> queryable,
      Type elementType,
      int maxRowCount,
      OptiqCatalogReader catalogReader,
      RelOptPlanner planner) {
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    final EnumerableRel.Prefer prefer;
    if (elementType == Object[].class) {
      prefer = EnumerableRel.Prefer.ARRAY;
    } else {
      prefer = EnumerableRel.Prefer.CUSTOM;
    }
    final OptiqPreparingStmt preparingStmt =
        new OptiqPreparingStmt(
            context,
            catalogReader,
            typeFactory,
            context.getRootSchema(),
            prefer,
            planner,
            EnumerableConvention.INSTANCE);

    final RelDataType x;
    final Prepare.PreparedResult preparedResult;
    if (sql != null) {
      assert queryable == null;
      final OptiqConnectionConfig config = context.config();
      SqlParser parser = SqlParser.create(SqlParserImpl.FACTORY, sql,
          config.quoting(), config.unquotedCasing(), config.quotedCasing());
      SqlNode sqlNode;
      try {
        sqlNode = parser.parseStmt();
      } catch (SqlParseException e) {
        throw new RuntimeException("parse failed", e);
      }

      Hook.PARSE_TREE.run(new Object[] {sql, sqlNode});

      final OptiqSchema rootSchema = context.getRootSchema();
      final ChainedSqlOperatorTable opTab =
          new ChainedSqlOperatorTable(
              ImmutableList.of(SqlStdOperatorTable.instance(), catalogReader));
      final SqlValidator validator =
          new OptiqSqlValidator(opTab, catalogReader, typeFactory);
      validator.setIdentifierExpansion(true);

      final List<Prepare.Materialization> materializations =
          config.materializationsEnabled()
              ? MaterializationService.instance().query(rootSchema)
              : ImmutableList.<Prepare.Materialization>of();
      for (Prepare.Materialization materialization : materializations) {
        populateMaterializations(context, planner, materialization);
      }
      preparedResult = preparingStmt.prepareSql(
          sqlNode, Object.class, validator, true, materializations);
      switch (sqlNode.getKind()) {
      case INSERT:
      case EXPLAIN:
        // FIXME: getValidatedNodeType is wrong for DML
        x = RelOptUtil.createDmlRowType(sqlNode.getKind(), typeFactory);
        break;
      default:
        x = validator.getValidatedNodeType(sqlNode);
      }
    } else {
      assert queryable != null;
      x = context.getTypeFactory().createType(elementType);
      preparedResult =
          preparingStmt.prepareQueryable(queryable, x);
    }

    final List<AvaticaParameter> parameters = new ArrayList<AvaticaParameter>();
    final RelDataType parameterRowType = preparedResult.getParameterRowType();
    for (RelDataTypeField field : parameterRowType.getFieldList()) {
      RelDataType type = field.getType();
      parameters.add(
          new AvaticaParameter(
              false,
              getPrecision(type),
              getScale(type),
              getTypeOrdinal(type),
              getTypeName(type),
              getClassName(type),
              field.getName()));
    }

    RelDataType jdbcType = makeStruct(typeFactory, x);
    final List<List<String>> originList = preparedResult.getFieldOrigins();
    final List<ColumnMetaData> columns =
        getColumnMetaDataList(typeFactory, x, jdbcType, originList);
    Class resultClazz = null;
    if (preparedResult instanceof Typed) {
      resultClazz = (Class) ((Typed) preparedResult).getElementType();
    }
    return new PrepareResult<T>(
        sql,
        parameters,
        jdbcType,
        columns,
        maxRowCount,
        preparedResult.getBindable(),
        resultClazz);
  }

  private List<ColumnMetaData> getColumnMetaDataList(
      JavaTypeFactory typeFactory, RelDataType x, RelDataType jdbcType,
      List<List<String>> originList) {
    final List<ColumnMetaData> columns =
        new ArrayList<ColumnMetaData>();
    for (Ord<RelDataTypeField> pair : Ord.zip(jdbcType.getFieldList())) {
      final RelDataTypeField field = pair.e;
      RelDataType type = field.getType();
      List<String> origins = originList.get(pair.i);
      Class clazz =
          (Class) typeFactory.getJavaClass(
              x.isStruct()
                  ? x.getFieldList().get(pair.i).getType()
                  : type);
      final ColumnMetaData.Rep rep =
          Util.first(ColumnMetaData.Rep.VALUE_MAP.get(clazz),
              ColumnMetaData.Rep.OBJECT);
      assert rep != null;
      columns.add(
          new ColumnMetaData(
              columns.size(),
              false,
              true,
              false,
              false,
              type.isNullable() ? 1 : 0,
              true,
              type.getPrecision(),
              field.getName(),
              origin(origins, 0),
              origin(origins, 2),
              getPrecision(type),
              getScale(type),
              origin(origins, 1),
              null,
              getTypeOrdinal(type),
              getTypeName(type),
              true,
              false,
              false,
              getClassName(type),
              rep));
    }
    return columns;
  }

  private static String origin(List<String> origins, int offsetFromEnd) {
    return origins == null || offsetFromEnd >= origins.size()
        ? null
        : origins.get(origins.size() - 1 - offsetFromEnd);
  }

  private int getTypeOrdinal(RelDataType type) {
    return type.getSqlTypeName().getJdbcOrdinal();
  }

  private static String getClassName(RelDataType type) {
    return null;
  }

  private static int getScale(RelDataType type) {
    return type.getScale() == RelDataType.SCALE_NOT_SPECIFIED
        ? 0
        : type.getScale();
  }

  private static int getPrecision(RelDataType type) {
    return type.getPrecision() == RelDataType.PRECISION_NOT_SPECIFIED
        ? 0
        : type.getPrecision();
  }

  private static String getTypeName(RelDataType type) {
    SqlTypeName sqlTypeName = type.getSqlTypeName();
    if (type instanceof RelDataTypeFactoryImpl.JavaType) {
      // We'd rather print "INTEGER" than "JavaType(int)".
      return sqlTypeName.getName();
    }
    switch (sqlTypeName) {
    case INTERVAL_YEAR_MONTH:
    case INTERVAL_DAY_TIME:
      // e.g. "INTERVAL_MONTH" or "INTERVAL_YEAR_MONTH"
      return "INTERVAL_"
          + type.getIntervalQualifier().toString().replace(' ', '_');
    default:
      return type.toString(); // e.g. "VARCHAR(10)", "INTEGER ARRAY"
    }
  }

  protected void populateMaterializations(Context context,
      RelOptPlanner planner, Prepare.Materialization materialization) {
    // REVIEW: initialize queryRel and tableRel inside MaterializationService,
    // not here?
    try {
      final OptiqSchema schema = materialization.materializedTable.schema;
      OptiqCatalogReader catalogReader =
          new OptiqCatalogReader(
              schema.root(),
              context.config().caseSensitive(),
              Util.skipLast(materialization.materializedTable.path()),
              context.getTypeFactory());
      final OptiqMaterializer materializer =
          new OptiqMaterializer(context, catalogReader, schema, planner);
      materializer.populate(materialization);
    } catch (Exception e) {
      throw new RuntimeException("While populating materialization "
          + materialization.materializedTable.path(), e);
    }
  }

  private static RelDataType makeStruct(
      RelDataTypeFactory typeFactory,
      RelDataType type) {
    if (type.isStruct()) {
      return type;
    }
    return typeFactory.builder().add("$0", type).build();
  }

  /** Executes a prepare action. */
  public <R> R perform(OptiqServerStatement statement,
      Frameworks.PrepareAction<R> action) {
    final Context context = statement.createPrepareContext();
    final JavaTypeFactory typeFactory = context.getTypeFactory();
    OptiqCatalogReader catalogReader = new OptiqCatalogReader(
        context.getRootSchema(),
        context.config().caseSensitive(),
        context.getDefaultSchemaPath(),
        typeFactory);
    final RexBuilder rexBuilder = new RexBuilder(typeFactory);
    final RelOptPlanner planner = createPlanner(context);
    final RelOptQuery query = new RelOptQuery(planner);
    final RelOptCluster cluster =
        query.createCluster(rexBuilder.getTypeFactory(), rexBuilder);
    return action.apply(cluster, catalogReader, context.getRootSchema().plus(),
        statement);
  }

  static class OptiqPreparingStmt extends Prepare
      implements RelOptTable.ViewExpander {
    private final RelOptPlanner planner;
    private final RexBuilder rexBuilder;
    protected final OptiqSchema schema;
    protected final RelDataTypeFactory typeFactory;
    private final EnumerableRel.Prefer prefer;

    private int expansionDepth;
    private SqlValidator sqlValidator;

    public OptiqPreparingStmt(Context context,
        CatalogReader catalogReader,
        RelDataTypeFactory typeFactory,
        OptiqSchema schema,
        EnumerableRel.Prefer prefer,
        RelOptPlanner planner,
        Convention resultConvention) {
      super(context, catalogReader, resultConvention);
      this.schema = schema;
      this.prefer = prefer;
      this.planner = planner;
      this.typeFactory = typeFactory;
      this.rexBuilder = new RexBuilder(typeFactory);
    }

    @Override
    protected void init(Class runtimeContextClass) {
    }

    public PreparedResult prepareQueryable(
        Queryable queryable,
        RelDataType resultType) {
      queryString = null;
      Class runtimeContextClass = Object.class;
      init(runtimeContextClass);

      final RelOptQuery query = new RelOptQuery(planner);
      final RelOptCluster cluster =
          query.createCluster(
              rexBuilder.getTypeFactory(), rexBuilder);

      RelNode rootRel =
          new LixToRelTranslator(cluster, OptiqPreparingStmt.this)
              .translate(queryable);

      if (timingTracer != null) {
        timingTracer.traceTime("end sql2rel");
      }

      final RelDataType jdbcType =
          makeStruct(rexBuilder.getTypeFactory(), resultType);
      fieldOrigins = Collections.nCopies(jdbcType.getFieldCount(), null);
      parameterRowType = rexBuilder.getTypeFactory().builder().build();

      // Structured type flattening, view expansion, and plugging in
      // physical storage.
      rootRel = flattenTypes(rootRel, true);

      // Trim unused fields.
      rootRel = trimUnusedFields(rootRel);

      final List<Materialization> materializations = ImmutableList.of();
      rootRel = optimize(resultType, rootRel, materializations);

      if (timingTracer != null) {
        timingTracer.traceTime("end optimization");
      }

      return implement(
          resultType,
          rootRel,
          SqlKind.SELECT);
    }

    @Override
    protected SqlToRelConverter getSqlToRelConverter(
        SqlValidator validator,
        CatalogReader catalogReader) {
      SqlToRelConverter sqlToRelConverter =
          new SqlToRelConverter(
              this, validator, catalogReader, planner, rexBuilder,
              StandardConvertletTable.INSTANCE);
      sqlToRelConverter.setTrimUnusedFields(false);
      return sqlToRelConverter;
    }

    @Override
    protected EnumerableRelImplementor getRelImplementor(
        RexBuilder rexBuilder) {
      return new EnumerableRelImplementor(rexBuilder);
    }

    @Override
    protected boolean shouldAlwaysWriteJavaFile() {
      return false;
    }

    @Override
    public RelNode flattenTypes(
        RelNode rootRel,
        boolean restructure) {
      final SparkHandler spark = context.spark();
      if (spark.enabled()) {
        return spark.flattenTypes(planner, rootRel, restructure);
      }
      return rootRel;
    }

    @Override protected RelNode decorrelate(SqlToRelConverter sqlToRelConverter,
        SqlNode query, RelNode rootRel) {
      return sqlToRelConverter.decorrelate(query, rootRel);
    }

    @Override public RelNode expandView(
        RelDataType rowType,
        String queryString,
        List<String> schemaPath) {
      expansionDepth++;

      SqlParser parser = SqlParser.create(queryString);
      SqlNode sqlNode;
      try {
        sqlNode = parser.parseQuery();
      } catch (SqlParseException e) {
        throw new RuntimeException("parse failed", e);
      }
      // View may have different schema path than current connection.
      final CatalogReader catalogReader =
          this.catalogReader.withSchemaPath(schemaPath);
      SqlValidator validator = createSqlValidator(catalogReader);
      SqlNode sqlNode1 = validator.validate(sqlNode);

      SqlToRelConverter sqlToRelConverter =
          getSqlToRelConverter(validator, catalogReader);
      RelNode relNode =
          sqlToRelConverter.convertQuery(sqlNode1, true, false);

      --expansionDepth;
      return relNode;
    }

    private SqlValidatorImpl createSqlValidator(CatalogReader catalogReader) {
      return new SqlValidatorImpl(
          SqlStdOperatorTable.instance(), catalogReader,
          rexBuilder.getTypeFactory(), SqlConformance.DEFAULT) { };
    }

    @Override
    protected SqlValidator getSqlValidator() {
      if (sqlValidator == null) {
        sqlValidator = createSqlValidator(catalogReader);
      }
      return sqlValidator;
    }

    @Override
    protected PreparedResult createPreparedExplanation(
        RelDataType resultType,
        RelDataType parameterRowType,
        RelNode rootRel,
        boolean explainAsXml,
        SqlExplainLevel detailLevel) {
      return new OptiqPreparedExplain(
          resultType, parameterRowType, rootRel, explainAsXml, detailLevel);
    }

    @Override
    protected PreparedResult implement(
        RelDataType rowType,
        RelNode rootRel,
        SqlKind sqlKind) {
      RelDataType resultType = rootRel.getRowType();
      boolean isDml = sqlKind.belongsTo(SqlKind.DML);
      EnumerableRelImplementor relImplementor =
          getRelImplementor(rootRel.getCluster().getRexBuilder());
      ClassDeclaration expr =
          relImplementor.implementRoot((EnumerableRel) rootRel, prefer);
      String s = Expressions.toString(expr.memberDeclarations, "\n", false);

      if (DEBUG) {
        System.out.println();
        System.out.println(s);
      }

      Hook.JAVA_PLAN.run(s);

      final Bindable bindable;
      try {
        bindable = getBindable(expr, s);
      } catch (Exception e) {
        throw Helper.INSTANCE.wrap(
            "Error while compiling generated Java code:\n"
            + s,
            e);
      }

      if (timingTracer != null) {
        timingTracer.traceTime("end codegen");
      }

      if (timingTracer != null) {
        timingTracer.traceTime("end compilation");
      }

      return new PreparedResultImpl(
          resultType,
          parameterRowType,
          fieldOrigins,
          rootRel,
          mapTableModOp(isDml, sqlKind),
          isDml) {
        public String getCode() {
          throw new UnsupportedOperationException();
        }

        public Bindable getBindable() {
          return bindable;
        }

        public Type getElementType() {
          return ((Typed) bindable).getElementType();
        }
      };
    }

    private Bindable getBindable(ClassDeclaration expr,
        String s) throws CompileException, IOException {
      if (context.spark().enabled()) {
        return context.spark().compile(expr, s);
      }
      return (Bindable) ClassBodyEvaluator.createFastClassBodyEvaluator(
          new Scanner(null, new StringReader(s)),
          expr.name,
          Utilities.class,
          new Class[]{Bindable.class, Typed.class},
          getClass().getClassLoader());
    }
  }

  private static class OptiqPreparedExplain extends Prepare.PreparedExplain {
    public OptiqPreparedExplain(
        RelDataType resultType,
        RelDataType parameterRowType,
        RelNode rootRel,
        boolean explainAsXml,
        SqlExplainLevel detailLevel) {
      super(resultType, parameterRowType, rootRel, explainAsXml, detailLevel);
    }

    @Override
    public Bindable getBindable() {
      final String explanation = getCode();
      return new Bindable() {
        public Enumerable bind(DataContext dataContext) {
          return Linq4j.singletonEnumerable(explanation);
        }
      };
    }
  }

  interface ScalarTranslator {
    RexNode toRex(BlockStatement statement);
    List<RexNode> toRexList(BlockStatement statement);
    RexNode toRex(Expression expression);
    ScalarTranslator bind(
        List<ParameterExpression> parameterList, List<RexNode> values);
  }

  static class EmptyScalarTranslator implements ScalarTranslator {
    private final RexBuilder rexBuilder;

    public EmptyScalarTranslator(RexBuilder rexBuilder) {
      this.rexBuilder = rexBuilder;
    }

    public static ScalarTranslator empty(RexBuilder builder) {
      return new EmptyScalarTranslator(builder);
    }

    public List<RexNode> toRexList(BlockStatement statement) {
      final List<Expression> simpleList = simpleList(statement);
      final List<RexNode> list = new ArrayList<RexNode>();
      for (Expression expression1 : simpleList) {
        list.add(toRex(expression1));
      }
      return list;
    }

    public RexNode toRex(BlockStatement statement) {
      return toRex(Blocks.simple(statement));
    }

    private static List<Expression> simpleList(BlockStatement statement) {
      Expression simple = Blocks.simple(statement);
      if (simple instanceof NewExpression) {
        NewExpression newExpression = (NewExpression) simple;
        return newExpression.arguments;
      } else {
        return Collections.singletonList(simple);
      }
    }

    public RexNode toRex(Expression expression) {
      switch (expression.getNodeType()) {
      case MemberAccess:
        // Case-sensitive name match because name was previously resolved.
        return rexBuilder.makeFieldAccess(
            toRex(
                ((MemberExpression) expression).expression),
            ((MemberExpression) expression).field.getName(),
            true);
      case GreaterThan:
        return binary(
            expression, SqlStdOperatorTable.GREATER_THAN);
      case LessThan:
        return binary(expression, SqlStdOperatorTable.LESS_THAN);
      case Parameter:
        return parameter((ParameterExpression) expression);
      case Call:
        MethodCallExpression call = (MethodCallExpression) expression;
        SqlOperator operator =
            RexToLixTranslator.JAVA_TO_SQL_METHOD_MAP.get(call.method);
        if (operator != null) {
          return rexBuilder.makeCall(
              operator,
              toRex(
                  Expressions.<Expression>list()
                      .appendIfNotNull(call.targetExpression)
                      .appendAll(call.expressions)));
        }
        throw new RuntimeException(
            "Could translate call to method " + call.method);
      case Constant:
        final ConstantExpression constant =
            (ConstantExpression) expression;
        Object value = constant.value;
        if (value instanceof Number) {
          Number number = (Number) value;
          if (value instanceof Double || value instanceof Float) {
            return rexBuilder.makeApproxLiteral(
                BigDecimal.valueOf(number.doubleValue()));
          } else if (value instanceof BigDecimal) {
            return rexBuilder.makeExactLiteral((BigDecimal) value);
          } else {
            return rexBuilder.makeExactLiteral(
                BigDecimal.valueOf(number.longValue()));
          }
        } else if (value instanceof Boolean) {
          return rexBuilder.makeLiteral((Boolean) value);
        } else {
          return rexBuilder.makeLiteral(constant.toString());
        }
      default:
        throw new UnsupportedOperationException(
            "unknown expression type " + expression.getNodeType() + " "
            + expression);
      }
    }

    private RexNode binary(Expression expression, SqlBinaryOperator op) {
      BinaryExpression call = (BinaryExpression) expression;
      return rexBuilder.makeCall(
          op, toRex(Arrays.asList(call.expression0, call.expression1)));
    }

    private List<RexNode> toRex(List<Expression> expressions) {
      ArrayList<RexNode> list = new ArrayList<RexNode>();
      for (Expression expression : expressions) {
        list.add(toRex(expression));
      }
      return list;
    }

    public ScalarTranslator bind(
        List<ParameterExpression> parameterList, List<RexNode> values) {
      return new LambdaScalarTranslator(
          rexBuilder, parameterList, values);
    }

    public RexNode parameter(ParameterExpression param) {
      throw new RuntimeException("unknown parameter " + param);
    }
  }

  private static class LambdaScalarTranslator extends EmptyScalarTranslator {
    private final List<ParameterExpression> parameterList;
    private final List<RexNode> values;

    public LambdaScalarTranslator(
        RexBuilder rexBuilder,
        List<ParameterExpression> parameterList,
        List<RexNode> values) {
      super(rexBuilder);
      this.parameterList = parameterList;
      this.values = values;
    }

    public RexNode parameter(ParameterExpression param) {
      int i = parameterList.indexOf(param);
      if (i >= 0) {
        return values.get(i);
      }
      throw new RuntimeException("unknown parameter " + param);
    }
  }
}

// End OptiqPrepareImpl.java
