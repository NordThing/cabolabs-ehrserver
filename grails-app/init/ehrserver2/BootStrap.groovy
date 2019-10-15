package ehrserver2

import com.cabolabs.ehrserver.conf.ConfigurationItem
import com.cabolabs.security.*
import com.cabolabs.ehrserver.account.*
import com.cabolabs.openehr.opt.manager.OptManager
import com.cabolabs.ehrserver.ehr.clinical_documents.*
import com.cabolabs.ehrserver.openehr.common.change_control.*
import com.cabolabs.ehrserver.query.*
import com.cabolabs.ehrserver.openehr.common.generic.*

import com.cabolabs.ehrserver.openehr.ehr.*
import com.cabolabs.ehrserver.api.structures.*

import grails.core.GrailsApplication
import grails.util.Holders
import grails.converters.*

class BootStrap {

   def configurationService
   def operationalTemplateIndexerService
   def OPTService
   def versionFSRepoService
   
   GrailsApplication grailsApplication

   def init = { servletContext ->

      log.info "checking local repos exist and are accessible"
      // TODO

      log.info "extending base classes"
      extendClasses()

      log.info "creating configuration items"
      createConfig()

      log.info "creating plans"
      createPlans()

      log.info "creating roles"
      createRoles()

      log.info "creating default users"
      createUsers()

      log.info "creating default organizations"
      defaultOrganizations()

      log.info "assigning roles"
      assignRoles()

      log.info "template setup"
      setupTemplates()

      log.info "query groups"
      createQueryGroups()

      log.info "registering JSON marshallers"
      registerJSONMarshallers()
      registerXMLMarshallers()

      log.info "calculating initial accounts repo size"
      calculateRepoSizes()
   }
   def destroy = {
   }

   def calculateRepoSizes()
   {
      Account.list().each { account ->

         account.current_opt_repo_size = OPTService.getRepoSizeInBytesAccount(account)
         account.current_version_repo_size = versionFSRepoService.getRepoSizeInBytesAccount(account)
         account.save(flush: true, failOnError: true)
      }
   }

   def createPlans()
   {
      if (Plan.count() == 0)
      {
         // Create plans
         def plans = [
            new Plan(
              name:                            "Basic",
              max_organizations:               1,
              max_opts_per_organization:       5,
              max_api_tokens_per_organization: 5,
              repo_total_size_in_kb:           2.5*1000*1000, // 2.5 GB in kB (kB are 1000 B, KB or KiB are 1024 bytes)
              period:                          Plan.periods.MONTHLY
            ),
            new Plan(
              name:                            "Standard",
              max_organizations:               3,
              max_opts_per_organization:       10,
              max_api_tokens_per_organization: 20,
              repo_total_size_in_kb:           7.5*1000*1000,
              period:                          Plan.periods.MONTHLY
            ),
            new Plan(
              name:                           "Enterprise",
              max_organizations:               10,
              max_opts_per_organization:       25,
              max_api_tokens_per_organization: 999,
              repo_total_size_in_kb:           15*1000*1000,
              period:                          Plan.periods.MONTHLY
            ),
            new Plan(
              name:                            "Testing",
              max_organizations:               5,
              max_opts_per_organization:       20,
              max_api_tokens_per_organization: 3,
              repo_total_size_in_kb:           200000, // low for testing! (1MB in kB = 1000 kB)
              period:                          Plan.periods.MONTHLY
            )
         ]

         plans*.save(failOnError: true, flush: true)
      }
   }

   def registerJSONMarshallers()
   {
      JSON.registerObjectMarshaller(Ehr) { ehr ->
        return [uid: ehr.uid,
                dateCreated: ehr.dateCreated,
                subjectUid: ehr.subject.value,
                systemId: ehr.systemId]
      }

      JSON.registerObjectMarshaller(PaginatedResults) { pres ->

        pres.update() // updates and checks pagination values

        def res = [:]

        if (pres.list)
           res["${pres.listName}"] = (pres.list ?: []) // prevents null on the json
        else
           res["${pres.listName}"] = (pres.map ?: [:])

        res.pagination = [
           'max': pres.max,
           'offset': pres.offset,
           nextOffset: pres.nextOffset, // TODO: verificar que si la cantidad actual es menor que max, el nextoffset debe ser igual al offset
           prevOffset: pres.prevOffset
        ]

        if (pres.timing != null) res.timing = pres.timing.toString() + ' ms'

        return res
      }

      JSON.registerObjectMarshaller(CommitResult) { cres ->

         def res = [:]
         res.type = cres.type
         res.message = cres.message
         res.versions = []

         if (cres.versions)
         {
            res.versions = cres.versions
         }

         return res
      }

      JSON.registerObjectMarshaller(CompositionIndex) { composition ->
        return [uid: composition.uid,
                category:        composition.category,
                startTime:       composition.startTime,
                subjectId:       composition.subjectId,
                ehrUid:          composition.ehrUid,
                templateId:      composition.templateId,
                archetypeId:     composition.archetypeId,
                lastVersion:     composition.lastVersion,
                parent:          composition.getParent().uid]
      }

      JSON.registerObjectMarshaller(Version) { version ->
        return [uid:                 version.uid,
                precedingVersionUid: version.precedingVersionUid,
                lifecycleState:      version.lifecycleState,
                commitAudit:         version.commitAudit,
                data:                version.data]
      }

      JSON.registerObjectMarshaller(Date) {
         return it?.format(Holders.config.app.l10n.db_datetime_format)
      }

      JSON.registerObjectMarshaller(Query) { q ->
         def j = [uid: q.uid,
                 name: q.name,
                 format: q.format,
                 type: q.type,
                 author: q.author]

         if (q.type == 'composition')
         {
            j << [templateId: q.templateId]
            j << [criteria:   q.where.collect { expression_item ->
                                 [ criteria: [archetypeId: expression_item.criteria.archetypeId, path: expression_item.criteria.path, conditions: expression_item.criteria.getCriteriaMap()],
                                   left_assoc: expression_item.left_assoc,
                                   right_assoc: expression_item.right_assoc ]
                              }]
         }
         else
         {
            j << [group: q.group] // Group is only for datavalue
            j << [projections: q.select.collect { [archetypeId: it.archetypeId, path: it.path, rmTypeName: it.rmTypeName] }]
         }

         return j
      }

      JSON.registerObjectMarshaller(User) { u ->
        return [
               //username: u.username,
                email: u.email
               ]
      }

      JSON.registerObjectMarshaller(DoctorProxy) { doctor ->
        return [namespace: doctor.namespace,
                type: doctor.type,
                value: doctor.value,
                name: doctor.name]
      }

      JSON.registerObjectMarshaller(AuditDetails) { audit ->
        def a = [timeCommitted: audit.timeCommitted,
                 committer: audit.committer, // DoctorProxy
                 systemId: audit.systemId]
        // audit for contributions have changeType null, so we avoid to add it here if it is null
        if (audit.changeType) a << [changeType: audit.changeType.toString()]
        return a
      }

      JSON.registerObjectMarshaller(Contribution) { contribution ->
        return [uid: contribution.uid,
                ehrUid: contribution.ehr.uid,
                versions: contribution.versions.uid, // list of uids
                audit: contribution.audit // AuditDetails
               ]
      }

      JSON.registerObjectMarshaller(OperationalTemplateIndex) { opt ->
        return [templateId:  opt.templateId,
                concept:     opt.concept,
                language:    opt.language,
                uid:         opt.uid,
                externalUid: opt.externalUid,
                archetypeId: opt.archetypeId,
                archetypeConcept: opt.archetypeConcept,
                organizationUid:  opt.organizationUid,
                setID:       opt.setId,
                versionNumber: opt.versionNumber]
      }


      JSON.registerObjectMarshaller(Organization) { o ->
        return [uid: o.uid,
                name: o.name,
                number: o.number
               ]
      }


   }

   def registerXMLMarshallers()
   {
      XML.registerObjectMarshaller(Ehr) { ehr, xml ->
         xml.build {
            uid(ehr.uid)
            dateCreated(ehr.dateCreated)
            subjectUid(ehr.subject.value)
            systemId(ehr.systemId)
         }
      }

      XML.registerObjectMarshaller(PaginatedResults) { pres, xml ->

        pres.update() // updates and checks pagination values

        // Our list marshaller to customize the name
        xml.startNode pres.listName

           if (pres.list)
              xml.convertAnother (pres.list ?: []) // this works, generates "ehr" nodes
           else
              xml.convertAnother (pres.map ?: [:])

           /* doesnt generate the patient root, trying with ^
           pres.list.each { item ->
              xml.convertAnother item // marshaller fot the item type should be declared
           }
           */

        xml.end()

        xml.startNode 'pagination'
           xml.startNode 'max'
           xml.chars pres.max.toString() // integer fails for .chars
           xml.end()
           xml.startNode 'offset'
           xml.chars pres.offset.toString()
           xml.end()
           xml.startNode 'nextOffset'
           xml.chars pres.nextOffset.toString()
           xml.end()
           xml.startNode 'prevOffset'
           xml.chars pres.prevOffset.toString()
           xml.end()
        xml.end()

        // TODO: timing
      }

      XML.registerObjectMarshaller(CommitResult) { cres, xml ->

         xml.build {
            type(cres.type)
            message(cres.message)
         }

         xml.startNode 'versions' //cres.root

            if (cres.versions)
            {
               xml.convertAnother (cres.versions ?: [])
            }

         xml.end()
      }

      XML.registerObjectMarshaller(CompositionIndex) { composition, xml ->
        xml.build {
          uid(composition.uid)
          category(composition.category)
          startTime(composition.startTime)
          subjectId(composition.subjectId)
          ehrUid(composition.ehrUid)
          templateId(composition.templateId)
          archetypeId(composition.archetypeId)
          lastVersion(composition.lastVersion)
          parent(composition.getParent().uid)
        }
      }

      XML.registerObjectMarshaller(Version) { version, xml ->
         xml.build {
            uid(version.uid)
            precedingVersionUid(version.precedingVersionUid)
            lifecycleState(version.lifecycleState)
            commitAudit(version.commitAudit)
            data(version.data)
         }
      }

      XML.registerObjectMarshaller(Date) {
        return it?.format(Holders.config.app.l10n.db_datetime_format)
      }

      XML.registerObjectMarshaller(Query) { q, xml ->
         xml.build {
            uid(q.uid)
            name(q.name)
            format(q.format)
            type(q.type)
            author(q.author)
         }

         if (q.type == 'composition')
         {
            xml.startNode 'templateId'
               xml.chars (q.templateId ?: '') // fails if null!
            xml.end()

            def criteriaMap
            def _value
            //q.where.each { criteria -> // with this the criteria clases are marshalled twice, it seems the each is returning the criteria instead of just processing the xml format creation.
            for (expression_item in q.where) // works ok, so we need to avoid .each
            {
               xml.startNode 'expression_item'
                  if (expression_item.left_assoc)
                  {
                     xml.startNode 'left_assoc'
                     xml.chars expression_item.left_assoc
                     xml.end()
                  }
                  if (expression_item.right_assoc)
                  {
                     xml.startNode 'right_assoc'
                     xml.chars expression_item.right_assoc
                     xml.end()
                  }
                  xml.startNode 'criteria'
                     xml.startNode 'archetypeId'
                        xml.chars expression_item.criteria.archetypeId
                     xml.end()
                     xml.startNode 'path'
                        xml.chars expression_item.criteria.path
                     xml.end()
                     xml.startNode 'conditions'

                       criteriaMap = expression_item.criteria.getCriteriaMap() // [attr: [operand: value]] value can be a list
                       criteriaMap.each { attr, cond ->

                          _value = cond.find{true}.value // can be a list, string, boolean, ...

                           xml.startNode "$attr"
                              xml.startNode 'operand'
                                 xml.chars cond.find{true}.key
                              xml.end()

                              if (_value instanceof List)
                              {
                                 xml.startNode 'list'
                                    _value.each { val ->
                                       if (val instanceof Date)
                                       {
                                          // FIXME: should use the XML date marshaller
                                          xml.startNode 'item'
                                             xml.chars val.format(Holders.config.app.l10n.ext_datetime_utcformat_nof, TimeZone.getTimeZone("UTC"))
                                          xml.end()
                                       }
                                       else
                                       {
                                          xml.startNode 'item'
                                             xml.chars val.toString() // chars fails if type is Double or other non string
                                          xml.end()
                                       }
                                    }
                                 xml.end()
                              }
                              else
                              {
                                 xml.startNode 'value'
                                    xml.chars _value.toString() // chars fails if type is Double or other non string
                                 xml.end()
                              }
                           xml.end()
                        }
                     xml.end()
                  xml.end()
               xml.end()
            }
         }
         else
         {
            xml.startNode 'group'
               xml.chars q.group
            xml.end()

            q.select.each { proj ->
               xml.startNode 'projection'
                  xml.startNode 'archetypeId'
                     xml.chars proj.archetypeId
                  xml.end()
                  xml.startNode 'path'
                     xml.chars proj.path
                  xml.end()
               xml.end()
            }
         }
      }

      XML.registerObjectMarshaller(User) { u, xml ->
        xml.build {
          //username(u.username)
          email(u.email)

        }
      }

      XML.registerObjectMarshaller(DoctorProxy) { doctor, xml ->
        xml.build {
          namespace(doctor.namespace)
          type(doctor.type)
          value(doctor.value)
          name(doctor.name)
        }
      }

      XML.registerObjectMarshaller(AuditDetails) { audit, xml ->
        xml.build {
          timeCommitted(audit.timeCommitted)
          committer(audit.committer) // DoctorProxy
          systemId(audit.systemId)
          if (audit.changeType) changeType(audit.changeType.toString())
        }
      }

      XML.registerObjectMarshaller(Contribution) { contribution, xml ->
        xml.build {
          uid(contribution.uid)
          ehrUid(contribution.ehr.uid)
          /*
           * <versions>
           *  <string>8b68a18c-bcb1... </string>
           * </versions>
           */
          //versions(contribution.versions.uid) // list of uids
          /* doesnt work, see below!
          versions {
             contribution.versions.uid.each { _vuid ->
                uid(_vuid)
             }
          }
          */
          audit(contribution.audit) // AuditDetails
        }

        // works!
        // https://jwicz.wordpress.com/2011/07/11/grails-custom-xml-marshaller/
        // http://docs.grails.org/2.5.3/api/grails/converters/XML.html
         /*
          * <versions>
          *  <uid>8b68a18c-bcb1... </uid>
          * </versions>
          */
        xml.startNode 'versions'
        contribution.versions.uid.each { _vuid ->
           xml.startNode 'uid'
           xml.chars _vuid
           xml.end()
        }
        xml.end()
      }

      XML.registerObjectMarshaller(OperationalTemplateIndex) { opt, xml ->
        xml.build {
          templateId(opt.templateId)
          concept(opt.concept)
          language(opt.language)
          uid(opt.uid)
          externalUid(opt.externalUid)
          archetypeId(opt.archetypeId)
          archetypeConcept(opt.archetypeConcept)
          organizationUid(opt.organizationUid)
          setId(opt.setId)
          versionNumber(opt.versionNumber)
        }
      }

      XML.registerObjectMarshaller(Organization) { o, xml ->
        xml.build {
          uid(o.uid)
          name(o.name)
          number(o.number)
        }
      }
   }

   def createQueryGroups()
   {
      new QueryGroup(name:'Ungrouped').save()
   }

   def setupTemplates()
   {
      // for the default organization
      def orgs = Organization.list()

      // in memory cache, loads files located in the namespace folder
      def optRepo = grailsApplication.config.getProperty('app.opt_repo')
      def optMan = OptManager.getInstance(optRepo.withTrailSeparator())
      orgs.each { org ->

         // needs to run first so files are copied into the namespace folder
         if (OperationalTemplateIndex.countByOrganizationUid(org.uid) == 0)
         {
            operationalTemplateIndexerService.setupBaseOpts(org.uid)
            operationalTemplateIndexerService.indexAll(org.uid)
         }

         log.info("Indexing OPTs for organization "+ org.uid)
         optMan.loadAll(org.uid)
      }
   }

   def defaultOrganizations()
   {
      def organizations = []
      if (Organization.count() == 0)
      {
         // Default organization
         organizations << new Organization(name: 'Default Organization', number: '123456', uid:'e9d13294-bce7-44e7-9635-8e906da0c914')

         // the account will save the orgs

         // Create default QueryGroup per organization, see https://github.com/ppazos/cabolabs-ehrserver/issues/982
         organizations.each { org ->
            new QueryGroup(name:'Ungrouped', organizationUid:org.uid).save(flush:true)
         }


         // Default account
         def account = new Account(contact: User.findByEmail('admin@cabolabs.com'), enabled: true, companyName: 'Default Account')
         organizations.each { org ->
            account.addToOrganizations(org)
         }
         account.save(failOnError:true, flush:true)


         // Assign plan
         def test_plan = Plan.findByName('Testing')
         if (!PlanAssociation.findByAccount(account))
         {
            test_plan.associate(account, new Date().clearTime())
         }
      }
      else organizations = Organization.list()

      return organizations
   }

   def createRoles()
   {
      if (Role.count() == 0 )
      {
         // Create roles
         def adminRole          = new Role(authority: Role.AD).save(failOnError: true, flush: true)
         def orgManagerRole     = new Role(authority: Role.OM).save(failOnError: true, flush: true)
         def accountManagerRole = new Role(authority: Role.AM).save(failOnError: true, flush: true)
         def userRole           = new Role(authority: Role.US).save(failOnError: true, flush: true)
      }
   }

   def createUsers()
   {
      // User encodes the password internally
      def users = [
         new User(
            email: 'admin@cabolabs.com',
            password: 'admin'
         ),
         new User(
            email: 'orgman@cabolabs.com',
            password: 'orgman'
         ),
         new User(
            email: 'user@cabolabs.com',
            password: 'user'
         )
      ]

      users.each { user->

         if (!user.save(flush:true))
         {
            println user.errors
         }
      }
   }

   def assignRoles()
   {
      if (UserRole.count() == 0)
      {
         UserRole.create( User.findByEmail("admin@cabolabs.com"),  Role.findByAuthority(Role.AD), Organization.get(1), true )
         UserRole.create( User.findByEmail("admin@cabolabs.com"),  Role.findByAuthority(Role.AM), Organization.get(1), true ) // admin will be the accman of the default org
         UserRole.create( User.findByEmail("orgman@cabolabs.com"), Role.findByAuthority(Role.OM), Organization.get(1), true )
         UserRole.create( User.findByEmail("user@cabolabs.com"),   Role.findByAuthority(Role.US), Organization.get(1), true )
      }
   }

   def extendClasses()
   {
      // Used by query builder, all return String
      String.metaClass.asSQLValue = { operand ->
        //if (operand == 'contains') return "'%"+ delegate +"%'" // Contains is translated to LIKE, we need the %
        if (['contains', 'contains_like'].contains(operand)) return "'%"+ delegate +"%'" // Contains is translated to LIKE, we need the %
        return "'"+ delegate +"'"
      }
      Double.metaClass.asSQLValue = { operand ->
        return delegate.toString()
      }
      Integer.metaClass.asSQLValue = { operand ->
        return delegate.toString()
      }
      Long.metaClass.asSQLValue = { operand ->
        return delegate.toString()
      }
      Date.metaClass.asSQLValue = { operand ->
        def formatterDateDB = new java.text.SimpleDateFormat( Holders.config.app.l10n.db_datetime_format )
        return "'"+ formatterDateDB.format( delegate ) +"'"
      }
      Boolean.metaClass.asSQLValue = { operand ->
        return delegate.toString()
      }

      String.metaClass.md5 = {
         return java.security.MessageDigest.getInstance("MD5").digest(delegate.bytes).encodeHex().toString()
      }

      // call String.randomNumeric(5)
      String.metaClass.static.randomNumeric = { digits ->
        def alphabet = ['0','1','2','3','4','5','6','7','8','9']
        new Random().with {
          (1..digits).collect { alphabet[ nextInt( alphabet.size() ) ] }.join()
        }
      }

      String.metaClass.static.random = { n ->
        def alphabet = 'a'..'z'
        new Random().with {
          (1..n).collect { alphabet[ nextInt( alphabet.size() ) ] }.join()
        }
      }

      String.metaClass.static.uuid = { ->
         java.util.UUID.randomUUID() as String
      }

      // adds trailing path separator to a file path if it doesnt have it
      String.metaClass.withTrailSeparator = {
         def PS = System.getProperty("file.separator")
         if (!delegate.endsWith(PS)) delegate += PS
         return delegate
      }
      String.metaClass.withLeadSeparator = {
         def PS = System.getProperty("file.separator")
         if (!delegate.startsWith(PS)) delegate = PS + delegate
         return delegate
      }

      // get the stack trace as string from an exception
      // can also get the first X lines of the trace
      Throwable.metaClass.traceString = { lines ->
         StringWriter ewriter = new StringWriter()
         PrintWriter printWriter = new PrintWriter( ewriter )
         org.codehaus.groovy.runtime.StackTraceUtils.sanitize(delegate).printStackTrace(printWriter)
         printWriter.flush()

         def trace = ewriter.toString().normalize()

         // return just the first X lines of the trace
         if (lines)
         {
            if (lines <= 0) lines = 1

            // find the last end of line for the number of lines requested
            def i = 0
            (1..lines).each {
               i = trace.indexOf("\n", i)
               i++
            }

            trace = trace.substring(0, i)
         }

         return trace
      }
   }

   def createConfig()
   {
      def conf = [
         new ConfigurationItem(key:'ehrserver.instance.id', value:'9cbabb12-c4ae-421c-868c-a68985123983', type:'string', blank:false, description:'EHRServer running instance ID'),
         new ConfigurationItem(key:'ehrserver.console.lists.max_items', value:'20', type:'number', blank:false, description:'Max number of items on the lists views for the Web Console'),
         new ConfigurationItem(key:'ehrserver.security.passwords.min_length', value:'6', type:'number', blank:false, description:'Minimum password size used on password reset'),
         new ConfigurationItem(key:'ehrserver.security.password_token.expiration', value:'1440', type:'number', blank:false, description:'Number of minutes after the password reset token expires')
      ]

      conf.each {
         if (ConfigurationItem.countByKey(it.key) == 0)
         {
            if (!it.save(flush: true))
            {
               log.warn(it.errors.toString())
            }
         }
      }

      configurationService.refresh()
   }
}
