import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as projectApi from '@/api/project'
import type { ProjectRequest, ProjectTemplateRequest, ProjectVO } from '@/types'

/** 项目全局状态：项目列表、当前项目上下文 */
export const useProjectStore = defineStore('project', () => {
  const projects = ref<ProjectVO[]>([])
  const currentProject = ref<ProjectVO | null>(null)

  /** 加载我参与的项目列表 */
  async function fetchProjects() {
    projects.value = await projectApi.listMyProjects()
  }

  /** 创建项目并刷新列表 */
  async function createProject(data: ProjectRequest): Promise<ProjectVO> {
    const project = await projectApi.createProject(data)
    await fetchProjects()
    return project
  }

  /** 从模板创建项目并刷新列表 */
  async function createProjectFromTemplate(data: ProjectTemplateRequest): Promise<ProjectVO> {
    const project = await projectApi.createProjectFromTemplate(data)
    await fetchProjects()
    return project
  }

  /** 更新项目并刷新列表（同步当前项目上下文） */
  async function updateProject(id: number, data: ProjectRequest): Promise<ProjectVO> {
    const project = await projectApi.updateProject(id, data)
    await fetchProjects()
    if (currentProject.value?.id === id) {
      currentProject.value = project
    }
    return project
  }

  /** 设置当前项目上下文 */
  function setCurrentProject(project: ProjectVO | null) {
    currentProject.value = project
  }

  /** 删除项目并刷新列表 */
  async function removeProject(id: number) {
    await projectApi.deleteProject(id)
    if (currentProject.value?.id === id) {
      currentProject.value = null
    }
    await fetchProjects()
  }

  /** 归档项目并刷新列表（归档后从侧边栏移除，仅管理后台可见） */
  async function archiveProject(id: number) {
    await projectApi.archiveProject(id)
    if (currentProject.value?.id === id) {
      currentProject.value = null
    }
    await fetchProjects()
  }

  return {
    projects,
    currentProject,
    fetchProjects,
    createProject,
    createProjectFromTemplate,
    updateProject,
    setCurrentProject,
    removeProject,
    archiveProject,
  }
})
