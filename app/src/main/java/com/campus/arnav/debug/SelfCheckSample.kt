package com.campus.arnav.debug

/**
 * 离线自检用的教务页面样本。
 *
 * 结构照抄湖南强智教务（江苏师范大学科文学院）真实输出：table#timetable、表头 8 列、
 * 数据行首列是「第一大节…第五大节」、每格多个 div.kbcontent、同一格里多门课用
 * 「----------------------」分隔、节次写成 `[03-04-05节]`、教室写成 `【潘安湖致广楼】潘安湖致广楼-303`。
 */
object SelfCheckSample {

    fun timetable(): String = """
        <table id="timetable" width="100%" height="100%" border="1">
          <tr>
            <th width="40"></th>
            <th width="123">星期一</th>
            <th width="123">星期二</th>
          </tr>
          <tr>
            <td width="40" align="center">第一大节</td>
            <td width="123" valign='top'>
              <div class='item-box' style='display:none'><img src='/jsxsd/jsxx/ewmck?id=1'/></div>
              <div style="display: none;position: relative" class="kbcontent">
                <font>高等数学ⅠA</font><br/>
                <font title='教师'>车素兵</font><br/>
                <font title='周次(节次)'>6-9(周)[03-04-05节]</font><br/>
                <font>【潘安湖致广楼】潘安湖致广楼-303</font><br/>
                <font>通知单编号：202620271002794</font><br/>
                <font>班级：26软件2班</font><br/>
                <font>(讲课:72)</font><br/>
                ----------------------
                <font>高等数学ⅠA</font><br/>
                <font title='教师'>车素兵</font><br/>
                <font title='周次(节次)'>10-16(周)[03-04节]</font><br/>
                <font>【潘安湖致广楼】潘安湖致广楼-303</font><br/>
                <font>通知单编号：202620271002794</font><br/>
                <font>班级：26软件2班</font><br/>
                <font>(讲课:72)</font><br/>
              </div>
            </td>
            <td width="123" valign='top'><div class="kbcontent">&nbsp;</div></td>
          </tr>
          <tr>
            <td width="40" align="center">第二大节</td>
            <td width="123" valign='top'><div class="kbcontent">&nbsp;</div></td>
            <td width="123" valign='top'>
              <div style="display: none;position: relative" class="kbcontent">
                <font>大学英语A</font><br/>
                <font title='教师'>秦思</font><br/>
                <font title='周次(节次)'>6-16(周)[06-07节]</font><br/>
                <font>【潘安湖致广楼】潘安湖致广楼-109</font><br/>
                <font>通知单编号：202620271002770</font><br/>
                <font>班级：26软件2班</font><br/>
              </div>
            </td>
          </tr>
          <tr><td colspan="3">备注: 军事技能 1-3周;</td></tr>
        </table>
    """.trimIndent()

    fun exam(): String = """
        <table>
          <tr><th>课程名称</th><th>考试时间</th><th>考场</th><th>座位号</th></tr>
          <tr><td>高等数学ⅠA</td><td>2027-01-08 09:00</td><td>致广楼-303</td><td>12</td></tr>
        </table>
    """.trimIndent()

    fun grade(): String = """
        <table>
          <tr><th>开课学期</th><th>课程名称</th><th>成绩</th><th>学分</th><th>绩点</th></tr>
          <tr><td>2026-2027-1</td><td>高等数学ⅠA</td><td>88</td><td>5</td><td>3.8</td></tr>
        </table>
    """.trimIndent()
}