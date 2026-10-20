import request from '@/utils/request'

export function getAttendees() {
  return request.get('/user/attendee/list')
}

export function addAttendee(payload) {
  return request.post('/user/attendee/add', payload)
}

export function deleteAttendee(id) {
  return request.delete(`/user/attendee/delete/${id}`)
}
