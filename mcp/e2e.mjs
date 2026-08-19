/**
 * ProTask MCP 服务端到端验证脚本（不入仓，仅本地联调）。
 * 通过 stdio 连接 MCP server，验证只读工具与写入守卫行为。
 */
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'

import { readFileSync } from 'node:fs'

// 凭证支持环境变量或 testtmp/mcp-creds.json（{username,password}）
let USERNAME = process.env.PROTASK_USERNAME
let PASSWORD = process.env.PROTASK_PASSWORD
if (!USERNAME || !PASSWORD) {
  try {
    const creds = JSON.parse(readFileSync('../testtmp/mcp-creds.json', 'utf8'))
    USERNAME = creds.username
    PASSWORD = creds.password
  } catch {
    /* ignore */
  }
}
if (!USERNAME || !PASSWORD) {
  console.error('缺少 PROTASK_USERNAME / PROTASK_PASSWORD')
  process.exit(1)
}

const CREDS_FILE = new URL('../testtmp/mcp-creds.json', import.meta.url).pathname.replace(/^\//, '')
const transport = new StdioClientTransport({
  command: 'node',
  args: ['dist/index.js'],
  cwd: process.cwd(),
  env: {
    ...process.env,
    PROTASK_CREDS_FILE: CREDS_FILE,
    PROTASK_USERNAME: undefined,
    PROTASK_PASSWORD: undefined,
  },
  stderr: 'pipe',
})

const client = new Client({ name: 'mcp-e2e', version: '1.0.0' })
await client.connect(transport)

let pass = 0
let fail = 0
function check(name, cond, detail = '') {
  if (cond) {
    pass++
    console.log(`  PASS  ${name}`)
  } else {
    fail++
    console.log(`  FAIL  ${name} ${detail}`)
  }
}

function textOf(result) {
  return result.content?.map((c) => c.text).join('\n') ?? ''
}

// 1. 工具清单
const tools = await client.listTools()
const names = tools.tools.map((t) => t.name)
console.log('工具清单:', names.join(', '))
check('注册了 12 个工具', names.length === 12)

// 2. 只读：项目列表
const projects = await client.callTool({ name: 'list_projects', arguments: {} })
const projData = JSON.parse(textOf(projects))
check('list_projects 返回数组', Array.isArray(projData), textOf(projects).slice(0, 120))

if (projData.length === 0) {
  console.log('无可用项目，跳过写入用例')
} else {
  const project = projData[0]

  // 3. 项目详情（属性模板 + 成员）
  const detail = await client.callTool({ name: 'get_project', arguments: { projectId: project.id } })
  const detailData = JSON.parse(textOf(detail))
  check('get_project 含 cardProperties 数组', Array.isArray(detailData.cardProperties))
  check('get_project 含 members 数组', Array.isArray(detailData.members))

  // 4. 任务列表
  const tasks = await client.callTool({ name: 'list_tasks', arguments: { projectId: project.id } })
  check('list_tasks 成功', !tasks.isError)

  // 5. 写入守卫：模板外属性被拒绝
  const badProp = await client.callTool({
    name: 'create_task',
    arguments: {
      projectId: project.id,
      title: 'MCP守卫测试-模板外属性',
      properties: { not_exist_prop_xyz: 'x' },
    },
  })
  check('模板外属性被拒绝', badProp.isError === true && textOf(badProp).includes('不在项目属性模板中'))

  // 6. 写入守卫：HTML 注入被净化后正常创建
  const inject = await client.callTool({
    name: 'create_task',
    arguments: {
      projectId: project.id,
      title: '<script>alert(1)</script>MCP净化测试  任务',
      description: '第一行\r\n第二行<b>加粗</b>',
    },
  })
  const created = JSON.parse(textOf(inject))
  check('HTML 被净化（标题无标签）', !inject.isError && created.title === 'MCP净化测试 任务', textOf(inject).slice(0, 200))
  check('描述 HTML 标签被剥离', (created.description ?? '').includes('加粗') && !(created.description ?? '').includes('<b>'))

  // 7. 写入守卫：同名任务重复创建被拒绝
  const dup = await client.callTool({
    name: 'create_task',
    arguments: { projectId: project.id, title: 'MCP净化测试 任务' },
  })
  check('重复创建被拒绝', dup.isError === true && textOf(dup).includes('同名任务'))

  // 8. 属性校验（若项目有 status 模板则校验选项，否则验证非法选项被拒）
  if (detailData.cardProperties.some((p) => p.id === 'status')) {
    const badStatus = await client.callTool({
      name: 'update_task',
      arguments: {
        projectId: project.id,
        taskId: created.id,
        properties: { status: 'made_up_status' },
      },
    })
    check('自造选项值被拒绝', badStatus.isError === true && textOf(badStatus).includes('不在其选项内'))
    const okStatus = await client.callTool({
      name: 'update_task',
      arguments: { projectId: project.id, taskId: created.id, properties: { status: 'in_progress' } },
    })
    const okData = JSON.parse(textOf(okStatus))
    check('合法选项写入成功', !okStatus.isError && okData.properties?.status === 'in_progress')
  } else {
    console.log('  SKIP  项目无 status 属性模板，跳过选项校验用例')
  }

  // 9. 评论写入
  const comment = await client.callTool({
    name: 'add_comment',
    arguments: { projectId: project.id, taskId: created.id, content: '来自 MCP 的评论 <img src=x>' },
  })
  check('评论写入且 HTML 被净化', !comment.isError && textOf(comment).includes('来自 MCP 的评论') && !textOf(comment).includes('<img'))

  // 10. 危险操作守卫：confirm 缺失被 zod 拒绝（SDK 可能以异常或 isError 返回）
  let noConfirmRejected = false
  try {
    const res = await client.callTool({ name: 'delete_task', arguments: { taskId: created.id } })
    noConfirmRejected = res.isError === true && /confirm/i.test(textOf(res))
  } catch (e) {
    noConfirmRejected = /confirm/i.test(String(e))
  }
  check('删除缺少 confirm 被拒绝', noConfirmRejected)

  // 11. 清理：确认删除测试任务
  const del = await client.callTool({
    name: 'delete_task',
    arguments: { taskId: created.id, confirm: true },
  })
  check('confirm=true 删除成功', !del.isError)
}

// 12. 全局搜索
const search = await client.callTool({ name: 'search_tasks', arguments: { keyword: 'MCP' } })
check('search_tasks 成功', !search.isError)

console.log(`\n结果: ${pass} 通过, ${fail} 失败`)
await client.close()
process.exit(fail > 0 ? 1 : 0)
