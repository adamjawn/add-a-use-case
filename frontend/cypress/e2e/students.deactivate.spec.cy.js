// UC-STU-deactivate-student: the Deactivate button and warning in a student's details, and the teammate list on the
// peer evaluation page. The student API calls are stubbed, so these tests check what the UI shows and sends; the
// backend's own tests (StudentDeactivationIntegrationTest, ActiveAccountIntegrationTest) cover who may and what changes.
// Run against the dev stack (backend + `npm run dev`) with `npm run cy:open`: login is real.

const john = {
  id: 4,
  username: 'j.smith@abc.edu',
  enabled: true,
  firstName: 'John',
  lastName: 'Smith',
  email: 'j.smith@abc.edu',
  teamName: 'Team1'
}

const studentResult = (student) => ({ flag: true, code: 200, message: 'Find student successfully', data: student })

const studentPage = {
  flag: true,
  code: 200,
  message: 'Find students successfully',
  data: { content: [john], number: 0, size: 50, totalElements: 1 }
}

describe('Deactivate a student', () => {
  beforeEach(() => {
    cy.login('b.gates@abc.edu', '123456')
    cy.intercept('POST', '**/students/search*', { body: studentPage }).as('search')
    cy.intercept('POST', '**/students/4/deactivation', {
      body: { flag: true, code: 200, message: 'Deactivate student successfully', data: { ...john, enabled: false } }
    }).as('deactivate')
  })

  // Her details answer enabled the first time and `afterwards` from then on
  function openJohnsDetails(first = john, afterwards = first) {
    let calls = 0
    cy.intercept('GET', '**/students/4', (req) => {
      req.reply({ body: studentResult(calls++ === 0 ? first : afterwards) })
    }).as('details')
    cy.visit('/students')
    cy.wait('@search')
    cy.get('[data-cy=student-edit]').first().click()
    cy.wait('@details')
  }

  it('warns of every consequence before deactivating (step 4)', () => {
    openJohnsDetails()

    cy.get('[data-cy=student-status]').should('have.class', 'is-disabled') // The switch cannot deactivate
    cy.get('[data-cy=student-deactivate]').click()

    cy.get('.el-message-box').should('contain', 'Deactivate John Smith?')
    cy.get('[data-cy=deactivate-consequences] li').should('have.length', 5)
    cy.get('[data-cy=deactivate-consequences]')
      .should('contain', 'no longer be able to log in')
      .and('contain', 'signed out on the next action')
      .and('contain', 'stays on the team')
      .and('contain', 'Teammates will no longer evaluate John')
      .and('contain', 'weekly activity reports and peer evaluations are kept')
      .and('contain', 'can be reactivated later, unlike deleting')
  })

  it('sends nothing and keeps her details open on Cancel or Esc (5a)', () => {
    openJohnsDetails()

    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.el-message-box').contains('button', 'Cancel').click()
    cy.get('.el-message-box').should('not.exist')
    cy.get('[data-cy=student-deactivate]').should('be.visible')

    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.el-message-box').should('be.visible')
    cy.get('body').type('{esc}')
    cy.get('.el-message-box').should('not.exist')
    cy.get('[data-cy=student-deactivate]').should('be.visible')

    cy.get('@deactivate.all').should('have.length', 0)
  })

  it('deactivates on confirmation and says so (steps 5-6)', () => {
    openJohnsDetails(john, { ...john, enabled: false })

    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.el-message-box').contains('button', 'Deactivate').click()

    cy.wait('@deactivate')
    cy.get('.el-message--success').should('contain', 'John Smith has been deactivated.')
    cy.get('[data-cy=student-deactivate]').should('not.exist') // Reloaded as deactivated
    cy.get('@deactivate.all').should('have.length', 1)
  })

  it('offers reactivation, not deactivation, for a deactivated student (choice offered)', () => {
    openJohnsDetails({ ...john, enabled: false })

    cy.get('[data-cy=student-deactivate]').should('not.exist')
    cy.get('[data-cy=student-status]').should('not.have.class', 'is-disabled')
  })

  it('says she is already deactivated and reloads her details on a 409 (6b)', () => {
    cy.intercept('POST', '**/students/4/deactivation', {
      statusCode: 409,
      body: { flag: false, code: 409, message: 'This student is already deactivated.' }
    }).as('deactivate')
    openJohnsDetails(john, { ...john, enabled: false })

    cy.get('[data-cy=student-deactivate]').click()
    cy.get('.el-message-box').contains('button', 'Deactivate').click()

    cy.wait('@deactivate')
    cy.get('.el-message--error').should('contain', 'This student is already deactivated.')
    cy.get('[data-cy=student-deactivate]').should('not.exist')
    cy.get('[data-cy=student-status]').should('not.have.class', 'is-disabled')
  })
})

describe('Peer evaluation of a deactivated teammate', () => {
  it('does not offer a deactivated teammate (BR-deactivated-evaluatee)', () => {
    cy.login('j.smith@abc.edu', '123456')
    cy.intercept('GET', '**/students/teams/*', {
      body: {
        flag: true,
        code: 200,
        message: 'Find students by team id successfully',
        data: [
          john,
          { id: 5, username: 'e.hudson@abc.edu', enabled: false, firstName: 'Eric', lastName: 'Hudson', email: 'e.hudson@abc.edu' },
          { id: 6, username: 'j.moon@abc.edu', enabled: true, firstName: 'Jerry', lastName: 'Moon', email: 'j.moon@abc.edu' }
        ]
      }
    }).as('teammates')
    cy.intercept('GET', '**/evaluations/evaluators/*/week/*', {
      body: { flag: true, code: 200, message: 'Find evaluations successfully', data: [] }
    })

    cy.visit('/evaluations/submit-evaluations')
    cy.wait('@teammates')

    cy.contains('Jerry Moon').should('exist')
    cy.contains('Eric Hudson').should('not.exist')
  })
})
