<template>
  <el-form
    ref="studentForm"
    :model="studentBeingEdited"
    :rules="rules"
    label-width="110px"
    style="padding-right: 30px"
    label-position="left"
  >
    <el-form-item label="Id:">
      <el-input v-model="studentBeingEdited.id" disabled></el-input>
    </el-form-item>
    <el-form-item label="Username:" prop="username">
      <el-input v-model="studentBeingEdited.username"></el-input>
    </el-form-item>
    <el-form-item label="Status:" prop="enabled">
      <!-- An active student is deactivated with the Deactivate button, never here; a deactivated one is reactivated here -->
      <el-switch
        v-model="studentBeingEdited.enabled"
        :disabled="isActive"
        data-cy="student-status"
        inline-prompt
        active-text="Enabled"
        inactive-text="Disabled"
        style="--el-switch-on-color: #13ce66; --el-switch-off-color: #ff4949"
      />
    </el-form-item>
    <el-form-item label="First Name:" prop="firstName">
      <el-input v-model="studentBeingEdited.firstName"></el-input>
    </el-form-item>
    <el-form-item label="Last Name:" prop="lastName">
      <el-input v-model="studentBeingEdited.lastName"></el-input>
    </el-form-item>
    <el-form-item label="Email:" prop="email">
      <el-input v-model="studentBeingEdited.email"></el-input>
    </el-form-item>
    <el-form-item label="Team:" prop="teamName">
      <el-input v-model="studentBeingEdited.teamName" disabled></el-input>
    </el-form-item>
    <div style="display: flex; justify-content: end">
      <el-button
        v-if="isActive"
        type="danger"
        plain
        data-cy="student-deactivate"
        style="margin-right: auto"
        @click="deactivateThisStudent()"
      >
        Deactivate
      </el-button>
      <el-button @click="$emit('close-dialog')"> Cancel </el-button>
      <el-button type="primary" @click="updateExistingStudent()"> Confirm </el-button>
    </div>
  </el-form>
</template>

<script setup lang="ts">
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Student } from '@/apis/student/types'
import type { FormInstance } from 'element-plus'
import { deactivateStudent, findStudentById, updateStudent } from '@/apis/student'
import { h, ref, onMounted } from 'vue'

const { studentId } = defineProps(['studentId'])
const emit = defineEmits(['close-dialog', 'student-changed'])

// Whether the student's account is active as last loaded from the server, not as the switch currently shows
const isActive = ref<boolean>(false)

// Note that the structure of the userData is NOT the same as the User interface since the roles are stored as an array
const studentBeingEdited = ref<Student>({
  // Data of the user being added or edited
  id: NaN,
  username: '',
  enabled: false,
  firstName: '',
  lastName: '',
  email: ''
})

const studentForm = ref<FormInstance>() // Form reference

// Validation rules
const rules = {
  username: [
    { required: true, message: 'Please input your email as your username', trigger: 'blur' },
    {
      pattern: /^[^\s@]+@[^\s@]+\.[^\s@]+$/,
      message: 'Enter your email as username.',
      trigger: 'blur'
    }
  ],
  enabled: [{ required: true, message: 'Please select the status of the user.', trigger: 'blur' }],
  firstName: [
    { required: true, message: 'Please provide the first name of the user.', trigger: 'blur' }
  ],
  lastName: [
    { required: true, message: 'Please provide the last name of the user.', trigger: 'blur' }
  ],
  email: [
    { required: true, message: 'Please provide the email of the user.', trigger: 'blur' },
    { type: 'email', message: 'Please provide a valid email.', trigger: 'blur' }
  ]
}

async function loadStudent(studentId: number) {
  // Load the user to be edited
  const result = await findStudentById(studentId)

  studentBeingEdited.value.id = result.data.id as number
  studentBeingEdited.value.username = result.data.username
  studentBeingEdited.value.enabled = result.data.enabled
  studentBeingEdited.value.firstName = result.data.firstName
  studentBeingEdited.value.lastName = result.data.lastName
  studentBeingEdited.value.email = result.data.email
  studentBeingEdited.value.teamName = result.data.teamName
  isActive.value = result.data.enabled === true
}

onMounted(() => {
  loadStudent(studentId) // Load the artifact to be edited
})

// UC-STU-deactivate-student, steps 3-6
async function deactivateThisStudent() {
  const { id, firstName, lastName } = studentBeingEdited.value
  const name = `${firstName} ${lastName}`
  const consequences = [
    `${firstName} will no longer be able to log in. If ${firstName} is logged in now, ${firstName} is signed out on the next action.`,
    `${firstName} stays on the team.`,
    `Teammates will no longer evaluate ${firstName}.`,
    `${firstName}'s weekly activity reports and peer evaluations are kept.`,
    `${firstName} can be reactivated later, unlike deleting the student.`
  ]

  // Step 4: warn and ask for confirmation. Cancel, the close button and Esc all reject: nothing is sent (5a)
  try {
    await ElMessageBox.confirm(
      h(
        'ul',
        { 'data-cy': 'deactivate-consequences', style: 'padding-left: 18px; margin: 0' },
        consequences.map((line) => h('li', line))
      ),
      `Deactivate ${name}?`,
      { confirmButtonText: 'Deactivate', cancelButtonText: 'Cancel', type: 'warning' }
    )
  } catch {
    return
  }

  // Steps 5-6
  try {
    await deactivateStudent(id as number)
    ElMessage.success(`${name} has been deactivated.`)
  } catch (error) {
    // 6b: the shared request instance has toasted the server's message; reload to show the student as deactivated.
    // Any other error (6a's 403 goes to the forbidden page) is left to the shared request instance.
    if (!(axios.isAxiosError(error) && error.response?.status === 409)) return
  }
  await loadStudent(id as number)
  emit('student-changed')
}

async function updateExistingStudent() {
  await studentForm.value!.validate() // Validate the form

  const updatedStudent: Student = {
    id: studentBeingEdited.value.id,
    username: studentBeingEdited.value.username,
    enabled: studentBeingEdited.value.enabled,
    firstName: studentBeingEdited.value.firstName,
    lastName: studentBeingEdited.value.lastName,
    email: studentBeingEdited.value.email
  }
  console.log(updatedStudent)
  // Call the API to update the user
  await updateStudent(updatedStudent)

  ElMessage.success('Student updated successfully')
  emit('close-dialog')
}
</script>

<style scoped lang="scss"></style>
