/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.qp.plan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.exception.query.QueryProcessException;
import org.apache.iotdb.db.metadata.MManager;
import org.apache.iotdb.db.qp.Planner;
import org.apache.iotdb.db.qp.logical.Operator.OperatorType;
import org.apache.iotdb.db.qp.physical.PhysicalPlan;
import org.apache.iotdb.db.qp.physical.crud.AggregationPlan;
import org.apache.iotdb.db.qp.physical.crud.FillQueryPlan;
import org.apache.iotdb.db.qp.physical.crud.GroupByPlan;
import org.apache.iotdb.db.qp.physical.crud.QueryPlan;
import org.apache.iotdb.db.qp.physical.crud.RawDataQueryPlan;
import org.apache.iotdb.db.qp.physical.sys.AuthorPlan;
import org.apache.iotdb.db.qp.physical.sys.CreateTimeSeriesPlan;
import org.apache.iotdb.db.qp.physical.sys.DataAuthPlan;
import org.apache.iotdb.db.qp.physical.sys.LoadConfigurationPlan;
import org.apache.iotdb.db.qp.physical.sys.OperateFilePlan;
import org.apache.iotdb.db.qp.physical.sys.ShowPlan;
import org.apache.iotdb.db.query.fill.LinearFill;
import org.apache.iotdb.db.query.fill.PreviousFill;
import org.apache.iotdb.db.utils.EnvironmentUtils;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.impl.BinaryExpression;
import org.apache.iotdb.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.iotdb.tsfile.read.expression.impl.SingleSeriesExpression;
import org.apache.iotdb.tsfile.read.filter.TimeFilter;
import org.apache.iotdb.tsfile.read.filter.ValueFilter;
import org.apache.iotdb.tsfile.read.filter.factory.FilterFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PhysicalPlanTest {

  private Planner processor = new Planner();

  @Before
  public void before() throws QueryProcessException, MetadataException {
    MManager.getInstance().init();
    MManager.getInstance().setStorageGroup("root.vehicle");
    MManager.getInstance().addPath("root.vehicle.d1.s1", TSDataType.FLOAT, TSEncoding.PLAIN,
        CompressionType.UNCOMPRESSED, null);
    MManager.getInstance().addPath("root.vehicle.d2.s1", TSDataType.FLOAT, TSEncoding.PLAIN,
        CompressionType.UNCOMPRESSED, null);
    MManager.getInstance().addPath("root.vehicle.d3.s1", TSDataType.FLOAT, TSEncoding.PLAIN,
        CompressionType.UNCOMPRESSED, null);
    MManager.getInstance().addPath("root.vehicle.d4.s1", TSDataType.FLOAT, TSEncoding.PLAIN,
        CompressionType.UNCOMPRESSED, null);
  }

  @After
  public void clean() throws IOException {
    MManager.getInstance().clear();
    EnvironmentUtils.cleanAllDir();
  }

  @Test
  public void testMetadata() throws QueryProcessException {
    String metadata = "create timeseries root.vehicle.d1.s2 with datatype=INT32,encoding=RLE";
    Planner processor = new Planner();
    CreateTimeSeriesPlan plan = (CreateTimeSeriesPlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals(String.format("seriesPath: root.vehicle.d1.s2%n" + "resultDataType: INT32%n" +
        "encoding: RLE%nnamespace type: ADD_PATH%n" + "args: "), plan.toString());
  }

  @Test
  public void testMetadata2() throws QueryProcessException {
    String metadata = "create timeseries root.vehicle.d1.s2 with datatype=int32,encoding=rle";
    Planner processor = new Planner();
    CreateTimeSeriesPlan plan = (CreateTimeSeriesPlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals(String.format("seriesPath: root.vehicle.d1.s2%n" + "resultDataType: INT32%n" +
        "encoding: RLE%nnamespace type: ADD_PATH%n" + "args: "), plan.toString());
  }

  @Test
  public void testAuthor() throws QueryProcessException {
    String sql = "grant role xm privileges 'SET_STORAGE_GROUP','DELETE_TIMESERIES' on root.vehicle.d1.s1";
    Planner processor = new Planner();
    AuthorPlan plan = (AuthorPlan) processor.parseSQLToPhysicalPlan(sql);
    assertEquals(
        "userName: null\n" + "roleName: xm\n" + "password: null\n" + "newPassword: null\n"
            + "permissions: [0, 5]\n" + "nodeName: root.vehicle.d1.s1\n" + "authorType: GRANT_ROLE",
        plan.toString());
  }

  @Test
  public void testAggregation() throws QueryProcessException {
    String sqlStr = "select sum(d1.s1) " + "from root.vehicle "
        + "where time <= 51 or !(time != 100 and time < 460)";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    if (!plan.isQuery()) {
      fail();
    }
    AggregationPlan mergePlan = (AggregationPlan) plan;
    assertEquals("sum", mergePlan.getAggregations().get(0));
  }

  @Test
  public void testGroupBy1() throws QueryProcessException {
    String sqlStr =
        "select count(s1) " + "from root.vehicle.d1 " + "where s1 < 20 and time <= now() "
            + "group by([8,737], 3ms)";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    if (!plan.isQuery()) {
      fail();
    }
    GroupByPlan mergePlan = (GroupByPlan) plan;
    assertEquals(3L, mergePlan.getInterval());
    assertEquals(3L, mergePlan.getSlidingStep());
    assertEquals(8L, mergePlan.getStartTime());
    assertEquals(737L, mergePlan.getEndTime());
  }

  @Test
  public void testGroupBy2() throws QueryProcessException {
    String sqlStr =
        "select count(s1) " + "from root.vehicle.d1 " + "where s1 < 20 and time <= now() "
            + "group by([123,2017-6-2T12:00:12+07:00], 111ms)";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    if (!plan.isQuery()) {
      fail();
    }
    GroupByPlan mergePlan = (GroupByPlan) plan;
    assertEquals(111, mergePlan.getInterval());
  }

  @Test
  public void testGroupBy3() throws QueryProcessException {
    String sqlStr =
        "select count(s1) " + "from root.vehicle.d1 " + "where s1 < 20 and time <= now() "
            + "group by([2017-6-2T12:00:12+07:00,2017-6-12T12:00:12+07:00], 3h, 24h)";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    if (!plan.isQuery()) {
      fail();
    }
    GroupByPlan mergePlan = (GroupByPlan) plan;
    assertEquals(3 * 60 * 60 * 1000, mergePlan.getInterval());
    assertEquals(24 * 60 * 60 * 1000, mergePlan.getSlidingStep());
    assertEquals(1496379612000L, mergePlan.getStartTime());
    assertEquals(1497243612000L, mergePlan.getEndTime());
  }

  @Test
  public void testFill1() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time = 5000 Fill(int32[linear, 5m, 5m], boolean[previous, 5m])";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    if (!plan.isQuery()) {
      fail();
    }
    FillQueryPlan mergePlan = (FillQueryPlan) plan;
    assertEquals(5000, mergePlan.getQueryTime());
    assertEquals(300000,
        ((LinearFill) mergePlan.getFillType().get(TSDataType.INT32)).getBeforeRange());
    assertEquals(300000,
        ((LinearFill) mergePlan.getFillType().get(TSDataType.INT32)).getAfterRange());
    assertEquals(300000,
        ((PreviousFill) mergePlan.getFillType().get(TSDataType.BOOLEAN)).getBeforeRange());
  }

  @Test
  public void testFill2() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time = 5000 Fill(int32[linear], boolean[previous])";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    if (!plan.isQuery()) {
      fail();
    }
    int defaultFillInterval = IoTDBDescriptor.getInstance().getConfig().getDefaultFillInterval();
    FillQueryPlan mergePlan = (FillQueryPlan) plan;
    assertEquals(5000, mergePlan.getQueryTime());
    assertEquals(defaultFillInterval,
        ((LinearFill) mergePlan.getFillType().get(TSDataType.INT32)).getBeforeRange());
    assertEquals(defaultFillInterval,
        ((LinearFill) mergePlan.getFillType().get(TSDataType.INT32)).getAfterRange());
    assertEquals(defaultFillInterval,
        ((PreviousFill) mergePlan.getFillType().get(TSDataType.BOOLEAN)).getBeforeRange());
  }

  @Test
  public void testFill3() {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time = 5000 Fill(int32[linear, 5m], boolean[previous])";
    try {
      processor.parseSQLToPhysicalPlan(sqlStr);
    } catch (Exception e) {
      assertTrue(true);
    }
  }

  @Test
  public void testFill4() {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time > 5000 Fill(int32[linear], boolean[previous])";
    try {
      processor.parseSQLToPhysicalPlan(sqlStr);
    } catch (Exception e) {
      assertEquals("Only \"=\" can be used in fill function", e.getMessage());
    }
  }

  @Test
  public void testQuery1() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time > 5000";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new GlobalTimeExpression(TimeFilter.gt(5000L));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQuery2() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time > 50 and time <= 100";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new GlobalTimeExpression(
        FilterFactory.and(TimeFilter.gt(50L), TimeFilter.ltEq(100L)));
    assertEquals(expect.toString(), queryFilter.toString());

  }

  @Test
  public void testQuery3() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time > 50 and time <= 100 or s1 < 10";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new GlobalTimeExpression(
        FilterFactory.and(TimeFilter.gt(50L), TimeFilter.ltEq(100L)));
    expect = BinaryExpression.or(expect,
        new SingleSeriesExpression(new Path("root.vehicle.d1.s1"), ValueFilter.lt(10.0)));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQuery4() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time > 50 and time <= 100 and s1 < 10";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();

    IExpression expect = BinaryExpression.and(
        new SingleSeriesExpression(new Path("root.vehicle.d1.s1"), ValueFilter.lt(10.0)),
        new GlobalTimeExpression(FilterFactory.and(TimeFilter.gt(50L), TimeFilter.ltEq(100L))));

    assertEquals(expect.toString(), queryFilter.toString());

    Path path = new Path("root.vehicle.d1.s1");
    assertEquals(path, plan.getPaths().get(0));
  }

  @Test
  public void testQuery5() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > 20 or s1 < 10";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        FilterFactory.or(ValueFilter.gt(20.0), ValueFilter.lt(10.0)));
    assertEquals(expect.toString(), queryFilter.toString());

  }

  @Test
  public void testQuery6() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time > 20 or time < 10";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new GlobalTimeExpression(
        FilterFactory.or(TimeFilter.gt(20L), TimeFilter.lt(10L)));
    assertEquals(expect.toString(), queryFilter.toString());

  }

  @Test
  public void testQuery7() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE time > 2019-10-16 10:59:00+08:00 - 1d5h or time < 10";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new GlobalTimeExpression(
        FilterFactory.or(TimeFilter.gt(1571090340000L), TimeFilter.lt(10L)));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testLimitOffset() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1,root.vehicle.d2 WHERE time < 10 "
        + "limit 100 offset 10 slimit 1 soffset 1";
    QueryPlan plan = (QueryPlan) processor.parseSQLToPhysicalPlan(sqlStr);
    assertEquals(100, plan.getRowLimit());
    assertEquals(10, plan.getRowOffset());
    // NOTE that the parameters of the SLIMIT clause is not stored in the physicalPlan,
    // because the SLIMIT clause takes effect before the physicalPlan is finally generated.
  }

  @Test
  public void testQueryFloat1() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > 20.5e3";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(20.5e3));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat2() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > 20.5E-3";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(20.5e-3));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat3() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > 2.5";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(2.5));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat4() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > 2.5";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(2.5));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat5() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > -2.5";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(-2.5));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat6() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > -2.5E-1";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(-2.5e-1));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat7() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > 2.5E2";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(2.5e+2));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat8() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > .2e2";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(0.2e+2));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat9() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > .2";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(0.2));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat10() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > 2.";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(2.0));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat11() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > 2.";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(2.0));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat12() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > -2.";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(-2.0));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat13() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > -.2";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(-0.2));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testQueryFloat14() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 > -.2e2";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.gt(-20.0));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testInOperator() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 in (25, 30, 40)";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    Set<Float> values = new HashSet<>();
    values.add(25.0f);
    values.add(30.0f);
    values.add(40.0f);
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.in(values, false));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testNotInOperator() throws QueryProcessException {
    String sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE s1 not in (25, 30, 40)";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    IExpression queryFilter = ((RawDataQueryPlan) plan).getExpression();
    Set<Float> values = new HashSet<>();
    values.add(25.0f);
    values.add(30.0f);
    values.add(40.0f);
    IExpression expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.in(values, true));
    assertEquals(expect.toString(), queryFilter.toString());

    sqlStr = "SELECT s1 FROM root.vehicle.d1 WHERE not(s1 not in (25, 30, 40))";
    plan = processor.parseSQLToPhysicalPlan(sqlStr);
    queryFilter = ((RawDataQueryPlan) plan).getExpression();
    expect = new SingleSeriesExpression(new Path("root.vehicle.d1.s1"),
        ValueFilter.in(values, false));
    assertEquals(expect.toString(), queryFilter.toString());
  }

  @Test
  public void testGrantWatermarkEmbedding() throws QueryProcessException {
    String sqlStr = "GRANT WATERMARK_EMBEDDING to a,b";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    DataAuthPlan dataAuthPlan = (DataAuthPlan) plan;
    Assert.assertEquals(2, dataAuthPlan.getUsers().size());
    Assert.assertEquals(OperatorType.GRANT_WATERMARK_EMBEDDING, dataAuthPlan.getOperatorType());
  }

  @Test
  public void testRevokeWatermarkEmbedding() throws QueryProcessException {
    String sqlStr = "REVOKE WATERMARK_EMBEDDING from a,b";
    PhysicalPlan plan = processor.parseSQLToPhysicalPlan(sqlStr);
    DataAuthPlan dataAuthPlan = (DataAuthPlan) plan;
    Assert.assertEquals(2, dataAuthPlan.getUsers().size());
    Assert.assertEquals(OperatorType.REVOKE_WATERMARK_EMBEDDING, dataAuthPlan.getOperatorType());
  }

  @Test
  public void testConfiguration() throws QueryProcessException {
    String metadata = "load configuration";
    Planner processor = new Planner();
    LoadConfigurationPlan plan = (LoadConfigurationPlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals("LOAD_CONFIGURATION", plan.toString());
  }

  @Test
  public void testShowDynamicParameter() throws QueryProcessException {
    String metadata = "show dynamic parameter";
    Planner processor = new Planner();
    ShowPlan plan = (ShowPlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals("SHOW DYNAMIC_PARAMETER", plan.toString());
  }

  @Test
  public void testShowFlushInfo() throws QueryProcessException {
    String metadata = "show flush task info";
    Planner processor = new Planner();
    ShowPlan plan = (ShowPlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals("SHOW FLUSH_TASK_INFO", plan.toString());
  }

  @Test
  public void testLoadFiles() throws QueryProcessException {
    String filePath = "data" + File.separator + "213213441243-1-2.tsfile";
    String metadata = String.format("load %s", filePath);
    Planner processor = new Planner();
    OperateFilePlan plan = (OperateFilePlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals(String.format(
        "OperateFilePlan{file=%s, targetDir=null, autoCreateSchema=true, sgLevel=2, operatorType=LOAD_FILES}",
        filePath), plan.toString());

    metadata = String.format("load %s true", filePath);
    processor = new Planner();
    plan = (OperateFilePlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals(String.format(
        "OperateFilePlan{file=%s, targetDir=null, autoCreateSchema=true, sgLevel=2, operatorType=LOAD_FILES}",
        filePath), plan.toString());

    metadata = String.format("load %s false", filePath);
    processor = new Planner();
    plan = (OperateFilePlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals(String.format(
        "OperateFilePlan{file=%s, targetDir=null, autoCreateSchema=false, sgLevel=2, operatorType=LOAD_FILES}",
        filePath), plan.toString());

    metadata = String.format("load %s true 3", filePath);
    processor = new Planner();
    plan = (OperateFilePlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals(String.format(
        "OperateFilePlan{file=%s, targetDir=null, autoCreateSchema=true, sgLevel=3, operatorType=LOAD_FILES}",
        filePath), plan.toString());
  }

  @Test
  public void testRemoveFile() throws QueryProcessException {
    String filePath = "data" + File.separator + "213213441243-1-2.tsfile";
    String metadata = String.format("remove %s", filePath);
    Planner processor = new Planner();
    OperateFilePlan plan = (OperateFilePlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals(String.format(
        "OperateFilePlan{file=%s, targetDir=null, autoCreateSchema=false, sgLevel=0, operatorType=REMOVE_FILE}",
        filePath), plan.toString());
  }

  @Test
  public void testMoveFile() throws QueryProcessException {
    String filePath = "data" + File.separator + "213213441243-1-2.tsfile";
    String targetDir = "user" + File.separator + "backup";
    String metadata = String.format("move %s %s", filePath, targetDir);
    Planner processor = new Planner();
    OperateFilePlan plan = (OperateFilePlan) processor.parseSQLToPhysicalPlan(metadata);
    assertEquals(
        String.format(
            "OperateFilePlan{file=%s, targetDir=%s, autoCreateSchema=false, sgLevel=0, operatorType=MOVE_FILE}",
            filePath,
            targetDir), plan.toString());
  }

  @Test
  public void testDeduplicatedPath() throws Exception {
    String sqlStr = "select * from root.vehicle.d1,root.vehicle.d1,root.vehicle.d1";
    RawDataQueryPlan plan = (RawDataQueryPlan) processor.parseSQLToPhysicalPlan(sqlStr);
    Assert.assertEquals(1, plan.getDeduplicatedPaths().size());
    Assert.assertEquals(1, plan.getDeduplicatedDataTypes().size());
    Assert.assertEquals(new Path("root.vehicle.d1.s1"), plan.getDeduplicatedPaths().get(0));

    sqlStr = "select count(*) from root.vehicle.d1,root.vehicle.d1,root.vehicle.d1";
    plan = (RawDataQueryPlan) processor.parseSQLToPhysicalPlan(sqlStr);
    Assert.assertEquals(1, plan.getDeduplicatedPaths().size());
    Assert.assertEquals(1, plan.getDeduplicatedDataTypes().size());
    Assert.assertEquals(new Path("root.vehicle.d1.s1"), plan.getDeduplicatedPaths().get(0));
  }
}
